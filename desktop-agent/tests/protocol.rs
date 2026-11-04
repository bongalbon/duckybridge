use duckybridge_agent::{
    model::*,
    server::{router, App},
    store::{fingerprint_pem, Store},
};
use futures_util::{SinkExt, StreamExt};
use jsonwebtoken::{encode, Algorithm, EncodingKey, Header};
use serde_json::{json, Value};
use std::{
    collections::BTreeMap,
    sync::Arc,
    time::{Duration, Instant},
};
use tokio_tungstenite::{connect_async_tls_with_config, tungstenite::Message, Connector};

#[tokio::test]
async fn tls_pair_approve_execute_reconnect_and_revoke() {
    rustls::crypto::ring::default_provider()
        .install_default()
        .ok();
    let temp = tempfile::tempdir().unwrap();
    let store = Store(temp.path().to_path_buf());
    store.write("devices.json", &Vec::<Device>::new()).unwrap();
    store.write("pending.json", &Vec::<Device>::new()).unwrap();
    let rcgen::CertifiedKey { cert, key_pair } =
        rcgen::generate_simple_self_signed(vec!["localhost".into(), "127.0.0.1".into()]).unwrap();
    let client_key = rcgen::KeyPair::generate().unwrap();
    let device_id = fingerprint_pem(&client_key.public_key_pem()).unwrap();
    #[cfg(windows)]
    let program = format!(
        "{}\\System32\\whoami.exe",
        std::env::var("SystemRoot").unwrap()
    );
    #[cfg(not(windows))]
    let program = "/usr/bin/whoami".to_string();
    let config = Config {
        agent_id: "fixture-agent".into(),
        name: "Test PC".into(),
        cwd: temp.path().to_string_lossy().into(),
        commands: BTreeMap::from([(
            "whoami".into(),
            AllowedCommand {
                program,
                args: vec![],
            },
        )]),
    };
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    let tls = axum_server::tls_rustls::RustlsConfig::from_pem(
        cert.pem().into_bytes(),
        key_pair.serialize_pem().into_bytes(),
    )
    .await
    .unwrap();
    let server = tokio::spawn(
        axum_server::from_tcp_rustls(listener, tls).serve(
            router(App {
                store: store.clone(),
                config,
                pairing_until: Some(Instant::now() + Duration::from_secs(120)),
                slots: Arc::new(tokio::sync::Semaphore::new(8)),
            })
            .into_make_service(),
        ),
    );
    // The self-signed test identity must never be trusted implicitly.
    assert!(reqwest::Client::new()
        .get(format!("https://{addr}/v1/ws"))
        .send()
        .await
        .is_err());
    let http = reqwest::Client::builder()
        .add_root_certificate(reqwest::Certificate::from_pem(cert.pem().as_bytes()).unwrap())
        .build()
        .unwrap();
    let pair = http
        .post(format!("https://{addr}/v1/pair"))
        .json(&json!({"name":"Android fixture", "public_key_pem":client_key.public_key_pem()}))
        .send()
        .await
        .unwrap();
    assert_eq!(pair.status(), reqwest::StatusCode::OK);
    // Pairing alone never authorizes a device.
    assert!(store.devices().unwrap().is_empty());
    let mut pending: Vec<Device> = store.read("pending.json").unwrap();
    assert_eq!(pending[0].id, device_id);
    pending[0].allowed_commands = vec!["whoami".into()];
    pending[0].expires_at = now() + 3600;
    store.write("devices.json", &pending).unwrap();
    let mut roots = rustls::RootCertStore::empty();
    roots.add(cert.der().clone()).unwrap();
    let connector = Connector::Rustls(Arc::new(
        rustls::ClientConfig::builder()
            .with_root_certificates(roots)
            .with_no_client_auth(),
    ));
    let (mut ws, _) = connect_async_tls_with_config(
        format!("wss://{addr}/v1/ws"),
        None,
        false,
        Some(connector.clone()),
    )
    .await
    .unwrap();
    let hello: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
    let audience = hello["audience"].as_str().unwrap();
    let sign = |aud: &str, op: Operation| {
        let mut header = Header::new(Algorithm::ES256);
        header.kid = Some(device_id.clone());
        encode(
            &header,
            &Claims {
                v: 1,
                iss: "duckybridge-v1".into(),
                sub: device_id.clone(),
                aud: aud.into(),
                iat: now(),
                exp: now() + 30,
                jti: uuid::Uuid::new_v4().to_string(),
                op,
            },
            &EncodingKey::from_ec_pem(client_key.serialize_pem().as_bytes()).unwrap(),
        )
        .unwrap()
    };
    ws.send(Message::Text(
        sign(
            audience,
            Operation::Execute {
                command: "whoami".into(),
                timeout_secs: 5,
            },
        )
        .into(),
    ))
    .await
    .unwrap();
    let started: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
    assert_eq!(started["type"], "started");
    let result: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
    assert_eq!(result["result"]["exit_code"], 0);
    ws.close(None).await.unwrap();
    let (mut reconnected, _) =
        connect_async_tls_with_config(format!("wss://{addr}/v1/ws"), None, false, Some(connector))
            .await
            .unwrap();
    let hello2: Value = serde_json::from_str(
        reconnected
            .next()
            .await
            .unwrap()
            .unwrap()
            .to_text()
            .unwrap(),
    )
    .unwrap();
    let audience2 = hello2["audience"].as_str().unwrap();
    assert_ne!(audience, audience2);
    reconnected
        .send(Message::Text(sign(audience2, Operation::Revoke).into()))
        .await
        .unwrap();
    let revoked: Value = serde_json::from_str(
        reconnected
            .next()
            .await
            .unwrap()
            .unwrap()
            .to_text()
            .unwrap(),
    )
    .unwrap();
    assert_eq!(revoked["type"], "revoked");
    assert!(store.devices().unwrap().is_empty());
    server.abort();
}
