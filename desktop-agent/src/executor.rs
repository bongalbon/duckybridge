use crate::model::*;
use anyhow::{bail, Result};
use serde_json::{json, Value};
use std::process::Stdio;
use tokio::{
    io::{AsyncRead, AsyncReadExt},
    process::Command,
    sync::oneshot,
};

const OUTPUT_LIMIT: usize = 64 * 1024;
async fn bounded(mut input: impl AsyncRead + Unpin) -> Vec<u8> {
    let mut out = Vec::new();
    let mut buffer = [0u8; 4096];
    while let Ok(n) = input.read(&mut buffer).await {
        if n == 0 {
            break;
        }
        let keep = n.min(OUTPUT_LIMIT.saturating_sub(out.len()));
        out.extend_from_slice(&buffer[..keep]);
    }
    out
}

pub fn allowed(
    config: &Config,
    device: &Device,
    alias: &str,
    timeout: u64,
) -> Result<AllowedCommand> {
    if !(1..=60).contains(&timeout) {
        bail!("Délai autorisé : 1 à 60 secondes");
    }
    if !device.allowed_commands.iter().any(|a| a == alias) {
        bail!("Commande non autorisée pour cet appareil");
    }
    let cmd = config
        .commands
        .get(alias)
        .ok_or_else(|| anyhow::anyhow!("Commande inconnue"))?
        .clone();
    if !std::path::Path::new(&cmd.program).is_absolute() {
        bail!("Le programme doit avoir un chemin absolu");
    }
    Ok(cmd)
}

pub async fn execute(
    config: Config,
    cmd: AllowedCommand,
    timeout: u64,
    mut cancel: oneshot::Receiver<()>,
) -> Result<Value> {
    let mut child = Command::new(&cmd.program)
        .args(&cmd.args)
        .current_dir(&config.cwd)
        .env_clear()
        .env("LANG", "C.UTF-8")
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true)
        .spawn()?;
    let mut stdout = tokio::spawn(bounded(child.stdout.take().unwrap()));
    let mut stderr = tokio::spawn(bounded(child.stderr.take().unwrap()));
    let (status, reason) = tokio::select! {
        s = child.wait() => (Some(s?), "completed"),
        _ = tokio::time::sleep(std::time::Duration::from_secs(timeout)) => { child.kill().await?; (Some(child.wait().await?), "timeout") },
        _ = &mut cancel => { child.kill().await?; (Some(child.wait().await?), "cancelled") },
    };
    // A user-configured program could leave inherited pipe handles open in a descendant.
    let output = tokio::time::timeout(std::time::Duration::from_secs(2), async {
        (
            (&mut stdout).await.unwrap_or_default(),
            (&mut stderr).await.unwrap_or_default(),
        )
    })
    .await;
    let pipe_timeout = output.is_err();
    stdout.abort();
    stderr.abort();
    let (out, err) = output.unwrap_or_default();
    Ok(
        json!({"status":reason, "exit_code":status.and_then(|s| s.code()),
        "stdout":String::from_utf8_lossy(&out), "stderr":String::from_utf8_lossy(&err),
        "output_limit":OUTPUT_LIMIT, "truncated":pipe_timeout || out.len()==OUTPUT_LIMIT || err.len()==OUTPUT_LIMIT}),
    )
}
