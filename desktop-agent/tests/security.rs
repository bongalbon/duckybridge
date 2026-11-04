use duckybridge_agent::{
    auth::Authorizer,
    executor,
    model::*,
    store::{fingerprint_pem, Store},
};
use jsonwebtoken::{encode, Algorithm, EncodingKey, Header};
use std::collections::BTreeMap;

fn fixture() -> (tempfile::TempDir, Store, Config, Device, rcgen::KeyPair) {
    let temp = tempfile::tempdir().unwrap();
    let store = Store(temp.path().to_path_buf());
    let key = rcgen::KeyPair::generate().unwrap();
    let pem = key.public_key_pem();
    let device = Device {
        id: fingerprint_pem(&pem).unwrap(),
        name: "Test Android".into(),
        public_key_pem: pem,
        allowed_commands: vec!["whoami".into()],
        expires_at: now() + 3600,
    };
    #[cfg(windows)]
    let program = format!(
        "{}\\System32\\whoami.exe",
        std::env::var("SystemRoot").unwrap()
    );
    #[cfg(not(windows))]
    let program = "/usr/bin/whoami".to_string();
    let config = Config {
        agent_id: "test-agent".into(),
        name: "Test".into(),
        cwd: temp.path().to_string_lossy().into(),
        commands: BTreeMap::from([(
            "whoami".into(),
            AllowedCommand {
                program,
                args: vec![],
            },
        )]),
    };
    store.write("devices.json", &vec![device.clone()]).unwrap();
    (temp, store, config, device, key)
}
fn claims(audience: &str, device: &Device) -> Claims {
    Claims {
        v: 1,
        iss: "duckybridge-v1".into(),
        sub: device.id.clone(),
        aud: audience.into(),
        iat: now(),
        exp: now() + 30,
        jti: uuid::Uuid::new_v4().to_string(),
        op: Operation::Status,
    }
}
fn signed(c: &Claims, d: &Device, key: &rcgen::KeyPair) -> String {
    let mut header = Header::new(Algorithm::ES256);
    header.kid = Some(d.id.clone());
    encode(
        &header,
        c,
        &EncodingKey::from_ec_pem(key.serialize_pem().as_bytes()).unwrap(),
    )
    .unwrap()
}
#[test]
fn signed_request_and_replay_rejection() {
    let (_temp, store, config, device, key) = fixture();
    let mut auth = Authorizer::new(config);
    let token = signed(&claims(&auth.audience, &device), &device, &key);
    assert!(auth.verify(&token, &store).is_ok());
    assert!(auth.verify(&token, &store).is_err());
}
#[test]
fn revocation_is_effective_in_existing_session() {
    let (_temp, store, config, device, key) = fixture();
    let mut auth = Authorizer::new(config);
    assert!(auth
        .verify(
            &signed(&claims(&auth.audience, &device), &device, &key),
            &store
        )
        .is_ok());
    store.revoke(&device.id).unwrap();
    assert!(auth
        .verify(
            &signed(&claims(&auth.audience, &device), &device, &key),
            &store
        )
        .is_err());
}
#[test]
fn reconnect_rejects_old_session_audience() {
    let (_temp, store, config, device, key) = fixture();
    let auth = Authorizer::new(config.clone());
    let token = signed(&claims(&auth.audience, &device), &device, &key);
    let mut new_session = Authorizer::new(config);
    assert!(new_session.verify(&token, &store).is_err());
    assert!(new_session
        .verify(
            &signed(&claims(&new_session.audience, &device), &device, &key),
            &store
        )
        .is_ok());
}
#[test]
fn expired_future_tampered_unknown_and_wrong_signature_are_refused() {
    let (_temp, store, config, device, key) = fixture();
    let mut auth = Authorizer::new(config);
    let mut expired = claims(&auth.audience, &device);
    expired.exp = now() - 1;
    expired.iat = now() - 31;
    assert!(auth
        .verify(&signed(&expired, &device, &key), &store)
        .is_err());
    let mut future = claims(&auth.audience, &device);
    future.iat += 120;
    future.exp += 120;
    assert!(auth
        .verify(&signed(&future, &device, &key), &store)
        .is_err());
    let other = rcgen::KeyPair::generate().unwrap();
    assert!(auth
        .verify(
            &signed(&claims(&auth.audience, &device), &device, &other),
            &store
        )
        .is_err());
    let mut token = signed(&claims(&auth.audience, &device), &device, &key);
    token.replace_range(1..2, "a");
    assert!(auth.verify(&token, &store).is_err());
    let mut unknown = device.clone();
    unknown.id = "unknown".into();
    assert!(auth
        .verify(
            &signed(&claims(&auth.audience, &unknown), &unknown, &key),
            &store
        )
        .is_err());
}
#[test]
fn permissions_and_timeout_cannot_be_overridden_remotely() {
    let (_temp, _store, config, device, _) = fixture();
    assert!(executor::allowed(&config, &device, "whoami", 20).is_ok());
    assert!(executor::allowed(&config, &device, "whoami; rm -rf /", 20).is_err());
    assert!(executor::allowed(&config, &device, "whoami", 0).is_err());
    assert!(executor::allowed(&config, &device, "whoami", 61).is_err());
}
#[tokio::test]
async fn executes_with_stdout_exit_code_and_no_shell() {
    let (_temp, _store, config, device, _) = fixture();
    let cmd = executor::allowed(&config, &device, "whoami", 5).unwrap();
    let (_cancel, receiver) = tokio::sync::oneshot::channel();
    let result = executor::execute(config, cmd, 5, receiver).await.unwrap();
    assert_eq!(result["status"], "completed");
    assert_eq!(result["exit_code"], 0);
    assert!(!result["stdout"].as_str().unwrap().trim().is_empty());
}
#[tokio::test]
async fn cancellation_timeout_and_missing_executable() {
    let (_temp, _store, config, _, _) = fixture();
    #[cfg(not(windows))]
    let sleep = AllowedCommand {
        program: "/bin/sleep".into(),
        args: vec!["30".into()],
    };
    #[cfg(windows)]
    let sleep = AllowedCommand {
        program: format!(
            "{}\\System32\\ping.exe",
            std::env::var("SystemRoot").unwrap()
        ),
        args: vec!["-n".into(), "30".into(), "127.0.0.1".into()],
    };
    let (cancel, receiver) = tokio::sync::oneshot::channel();
    let task = tokio::spawn(executor::execute(
        config.clone(),
        sleep.clone(),
        60,
        receiver,
    ));
    tokio::time::sleep(std::time::Duration::from_millis(50)).await;
    cancel.send(()).unwrap();
    assert_eq!(task.await.unwrap().unwrap()["status"], "cancelled");
    let (_cancel, receiver) = tokio::sync::oneshot::channel();
    assert_eq!(
        executor::execute(config.clone(), sleep, 1, receiver)
            .await
            .unwrap()["status"],
        "timeout"
    );
    let (_cancel, receiver) = tokio::sync::oneshot::channel();
    assert!(executor::execute(
        config,
        AllowedCommand {
            program: "/does/not/exist".into(),
            args: vec![]
        },
        2,
        receiver
    )
    .await
    .is_err());
}
