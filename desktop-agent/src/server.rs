use crate::{
    auth::Authorizer,
    executor,
    model::*,
    store::{fingerprint_pem, Store},
};
use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        DefaultBodyLimit, State,
    },
    http::{HeaderMap, StatusCode},
    response::IntoResponse,
    routing::{get, post},
    Json, Router,
};
use futures_util::{SinkExt, StreamExt};
use serde::Deserialize;
use serde_json::{json, Value};
use std::{
    sync::Arc,
    time::{Duration, Instant},
};
use tokio::sync::{mpsc, oneshot, Semaphore};

#[derive(Clone)]
pub struct App {
    pub store: Store,
    pub config: Config,
    pub pairing_until: Option<Instant>,
    pub slots: Arc<Semaphore>,
}
pub fn router(app: App) -> Router {
    Router::new()
        .route("/v1/pair", post(pair))
        .route("/v1/ws", get(upgrade))
        .layer(DefaultBodyLimit::max(8192))
        .with_state(app)
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct PairRequest {
    name: String,
    public_key_pem: String,
}
async fn pair(
    State(app): State<App>,
    Json(req): Json<PairRequest>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let result = (|| -> anyhow::Result<Value> {
        anyhow::ensure!(
            app.pairing_until.is_some_and(|t| Instant::now() < t),
            "Fenêtre d'appairage fermée"
        );
        anyhow::ensure!(
            !req.name.trim().is_empty()
                && req.name.len() <= 80
                && !req.name.chars().any(char::is_control),
            "Nom invalide"
        );
        let id = fingerprint_pem(&req.public_key_pem)?;
        let _guard = app.store.lock()?;
        let mut pending: Vec<Device> = app.store.read("pending.json")?;
        pending.retain(|d| d.expires_at > now());
        anyhow::ensure!(
            pending.len() < 10 && !pending.iter().any(|d| d.id == id),
            "Demande déjà reçue ou limite atteinte"
        );
        pending.push(Device {
            id: id.clone(),
            name: req.name,
            public_key_pem: req.public_key_pem,
            allowed_commands: vec![],
            expires_at: now() + 120,
        });
        app.store.write("pending.json", &pending)?;
        Ok(
            json!({"v":1,"device_id":id,"agent_id":app.config.agent_id,"state":"pending_local_approval"}),
        )
    })();
    result
        .map(Json)
        .map_err(|e| (StatusCode::FORBIDDEN, e.to_string()))
}

async fn upgrade(
    State(app): State<App>,
    headers: HeaderMap,
    ws: WebSocketUpgrade,
) -> impl IntoResponse {
    if headers.contains_key("origin") {
        return (StatusCode::FORBIDDEN, "Clients navigateur refusés").into_response();
    }
    let permit = match app.slots.clone().try_acquire_owned() {
        Ok(permit) => permit,
        Err(_) => return (StatusCode::TOO_MANY_REQUESTS, "Trop de connexions").into_response(),
    };
    ws.max_message_size(16384)
        .max_frame_size(16384)
        .on_upgrade(move |socket| async move {
            let _permit = permit;
            session(socket, app).await;
        })
}

async fn session(socket: WebSocket, app: App) {
    let mut auth = Authorizer::new(app.config.clone());
    let (mut tx, mut rx) = socket.split();
    if tx.send(Message::Text(json!({"v":1,"type":"hello","audience":auth.audience,"agent_id":app.config.agent_id,"session_seconds":600}).to_string().into())).await.is_err() { return; }
    let (results_tx, mut results_rx) = mpsc::channel::<Value>(4);
    let mut running: Option<(String, String, oneshot::Sender<()>)> = None;
    let mut owner: Option<String> = None;
    let deadline = tokio::time::sleep(Duration::from_secs(600));
    tokio::pin!(deadline);
    let mut tick = tokio::time::interval(Duration::from_millis(500));
    let authentication_deadline = Instant::now() + Duration::from_secs(15);
    loop {
        tokio::select! {
            _ = &mut deadline => break,
            _ = tick.tick() => {
                if owner.is_none() && Instant::now() >= authentication_deadline { break; }
                if let Some(id) = &owner {
                    let valid = (|| -> anyhow::Result<bool> { let _g = app.store.lock()?; Ok(app.store.devices()?.iter().any(|d| &d.id == id && d.expires_at > now())) })();
                    if !matches!(valid, Ok(true)) { break; }
                }
            },
            Some(result) = results_rx.recv() => {
                running.take();
                if app.store.audit(json!({"type":"result", "device":owner,"request_id":result["request_id"],"status":result["result"]["status"]})).is_err() { break; }
                if tx.send(Message::Text(result.to_string().into())).await.is_err() { break; }
            },
            incoming = rx.next() => {
                let token = match incoming { Some(Ok(Message::Text(t))) => t, Some(Ok(Message::Ping(p))) => { if tx.send(Message::Pong(p)).await.is_err() { break; } continue; }, _ => break };
                let verified = auth.verify(&token, &app.store);
                let (claims, device) = match verified {
                    Ok(v) => v,
                    Err(_) => { let _ = app.store.audit(json!({"type":"authentication_rejected"})); let _ = tx.send(Message::Text(json!({"type":"error","message":"Authentification refusée : appairage, expiration ou rejeu"}).to_string().into())).await; break; }
                };
                if owner.as_ref().is_some_and(|id| id != &device.id) { break; }
                owner = Some(device.id.clone());
                let id = claims.jti;
                let response = match claims.op {
                    Operation::Status => json!({"type":"status","request_id":id,"name":app.config.name,"os":std::env::consts::OS,"commands":device.allowed_commands,"cwd":app.config.cwd}),
                    Operation::Execute { command, timeout_secs } => {
                        if running.is_some() { json!({"type":"error","request_id":id,"message":"Une commande est déjà en cours"}) }
                        else { match executor::allowed(&app.config, &device, &command, timeout_secs) {
                            Err(e) => json!({"type":"error","request_id":id,"message":e.to_string()}),
                            Ok(cmd) => {
                                if app.store.audit(json!({"type":"execute","device":device.id,"request_id":id,"command":command})).is_err() { break; }
                                let (cancel_tx, cancel_rx) = oneshot::channel();
                                running = Some((id.clone(), device.id, cancel_tx));
                                let config = app.config.clone(); let sender = results_tx.clone(); let request = id.clone();
                                tokio::spawn(async move {
                                    let result = executor::execute(config, cmd, timeout_secs, cancel_rx).await;
                                    let event = match result { Ok(value) => json!({"type":"result","request_id":request,"result":value}), Err(e) => json!({"type":"error","request_id":request,"message":e.to_string()}) };
                                    let _ = sender.send(event).await;
                                });
                                json!({"type":"started","request_id":id})
                            }
                        }}
                    },
                    Operation::Cancel { request_id } => {
                        if running.as_ref().is_some_and(|(rid, owner, _)| rid == &request_id && owner == &device.id) {
                            if app.store.audit(json!({"type":"cancel","device":device.id,"request_id":request_id})).is_err() { break; }
                            // Keep the busy marker until the executor sends its final result.
                            if let Some((rid, dev, cancel)) = running.take() { let _ = cancel.send(()); let (placeholder, _) = oneshot::channel(); running = Some((rid, dev, placeholder)); }
                        }
                        json!({"type":"cancel_requested","request_id":id})
                    },
                    Operation::Revoke => {
                        if app.store.audit(json!({"type":"revoke","device":device.id})).is_err() { break; }
                        if app.store.revoke(&device.id).is_err() { break; }
                        let _ = tx.send(Message::Text(json!({"type":"revoked","request_id":id}).to_string().into())).await;
                        break;
                    }
                };
                if tx.send(Message::Text(response.to_string().into())).await.is_err() { break; }
            }
        }
    }
    if owner.is_some() {
        let _ = app.store.audit(json!({"type":"session_closed", "device":owner, "active_request":running.as_ref().map(|(id, _, _)| id)}));
    }
    if let Some((_, _, cancel)) = running {
        let _ = cancel.send(());
    }
}
