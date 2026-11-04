use anyhow::{bail, Context, Result};
use clap::{Parser, Subcommand};
use duckybridge_agent::{
    model::*,
    server::{self, App},
    store::{self, Store},
};
use std::{
    fs,
    net::{IpAddr, SocketAddr},
    path::PathBuf,
    sync::Arc,
    time::{Duration, Instant},
};

#[derive(Parser)]
#[command(
    version,
    about = "DuckyBridge — agent local avec consentement explicite"
)]
struct Cli {
    #[arg(long, global = true)]
    data_dir: Option<PathBuf>,
    #[command(subcommand)]
    action: Action,
}
#[derive(Subcommand)]
enum Action {
    Init {
        #[arg(long)]
        name: String,
        #[arg(long, default_value = ".")]
        cwd: PathBuf,
    },
    Identity,
    Serve {
        #[arg(long, default_value = "127.0.0.1")]
        bind: IpAddr,
        #[arg(long, default_value_t = 9843)]
        port: u16,
        #[arg(long)]
        pairing: bool,
        #[arg(long)]
        discover: bool,
    },
    Pending,
    Approve {
        fingerprint: String,
        #[arg(long, value_delimiter = ',', required = true)]
        commands: Vec<String>,
    },
    Devices,
    Revoke {
        fingerprint: String,
    },
    Uninstall {
        #[arg(long)]
        confirm: bool,
    },
}

fn main() -> Result<()> {
    let cli = Cli::parse();
    let directory = match cli.data_dir {
        Some(d) => d,
        None => directories::ProjectDirs::from("org", "DuckyBridge", "DuckyBridge")
            .context("Dossier personnel indisponible")?
            .data_local_dir()
            .to_path_buf(),
    };
    let store = Store(directory);
    match cli.action {
        Action::Init { name, cwd } => {
            store::init(&store, &name, &cwd)?;
            println!("Identité créée. Utilisez identity puis serve --bind <IP privée> --pairing.");
        }
        Action::Identity => {
            let c: Config = store.read("config.json")?;
            println!("Agent : {}\nEmpreinte TLS SHA-256 : {}\nCompte : utilisateur courant\nRépertoire : {}", c.name, c.agent_id, c.cwd);
        }
        Action::Pending => {
            let _g = store.lock()?;
            for d in store
                .read::<Vec<Device>>("pending.json")?
                .iter()
                .filter(|d| d.expires_at > now())
            {
                println!("{}  {}  expire={}", d.id, d.name, d.expires_at);
            }
        }
        Action::Devices => {
            let _g = store.lock()?;
            for d in store.devices()? {
                println!(
                    "{}  {}  {:?}  expire={}",
                    d.id, d.name, d.allowed_commands, d.expires_at
                );
            }
        }
        Action::Approve {
            fingerprint,
            commands,
        } => {
            store.audit(serde_json::json!({"type":"local_approval_requested","device":fingerprint,"commands":commands}))?;
            {
                let _g = store.lock()?;
                let c: Config = store.read("config.json")?;
                anyhow::ensure!(
                    commands.iter().all(|a| c.commands.contains_key(a)),
                    "Commande absente de la politique locale"
                );
                let mut pending: Vec<Device> = store.read("pending.json")?;
                let mut device = pending
                    .iter()
                    .find(|d| d.id == fingerprint && d.expires_at > now())
                    .context("Demande inconnue ou expirée")?
                    .clone();
                device.allowed_commands = commands;
                device.expires_at = now() + 30 * 86400;
                let mut devices = store.devices()?;
                devices.retain(|d| d.id != fingerprint);
                devices.push(device);
                store.write("devices.json", &devices)?;
                pending.retain(|d| d.id != fingerprint);
                store.write("pending.json", &pending)?;
            }
            store.audit(serde_json::json!({"type":"local_approval","device":fingerprint}))?;
            println!("Appareil autorisé pour 30 jours.");
        }
        Action::Revoke { fingerprint } => {
            store.audit(serde_json::json!({"type":"local_revoke","device":fingerprint}))?;
            store.revoke(&fingerprint)?;
            println!("Accès révoqué.");
        }
        Action::Uninstall { confirm } => {
            if !confirm {
                bail!("Arrêtez l'agent puis ajoutez --confirm. Les clés et autorisations seront supprimées.");
            }
            // Only remove an initialized state directory, never an arbitrary supplied path.
            let c: Config = store.read("config.json")?;
            keyring::Entry::new("DuckyBridge", &c.agent_id)?.delete_credential()?;
            for file in [
                "config.json",
                "agent-cert.pem",
                "devices.json",
                "pending.json",
                "audit.jsonl",
                "state.lock",
            ] {
                let p = store.0.join(file);
                if p.exists() {
                    fs::remove_file(p)?;
                }
            }
            println!("Identité et autorisations supprimées. Supprimez le binaire manuellement.");
        }
        Action::Serve {
            bind,
            port,
            pairing,
            discover,
        } => {
            let local = match bind {
                IpAddr::V4(ip) => ip.is_loopback() || ip.is_private() || ip.is_link_local(),
                IpAddr::V6(ip) => ip.is_loopback() || (ip.segments()[0] & 0xfe00) == 0xfc00,
            };
            anyhow::ensure!(
                local,
                "Choisissez une adresse locale précise ; aucune écoute publique ou 0.0.0.0"
            );
            let config: Config = store.read("config.json")?;
            let key = zeroize::Zeroizing::new(
                keyring::Entry::new("DuckyBridge", &config.agent_id)?
                    .get_password()
                    .context("Impossible d'ouvrir le magasin sécurisé")?,
            );
            rustls::crypto::ring::default_provider()
                .install_default()
                .ok();
            let runtime = tokio::runtime::Runtime::new()?;
            runtime.block_on(async move {
                let tls = axum_server::tls_rustls::RustlsConfig::from_pem(fs::read(store.0.join("agent-cert.pem"))?, key.as_bytes().to_vec()).await?;
                let _mdns = if discover && !bind.is_loopback() {
                    let daemon = mdns_sd::ServiceDaemon::new()?;
                    let hostname = format!("db-{}.local.", &config.agent_id[..12]);
                    let info = mdns_sd::ServiceInfo::new("_duckybridge._tcp.local.", &config.name, &hostname, bind.to_string().as_str(), port, [("v", "1"), ("fingerprint", config.agent_id.as_str())].as_slice())?;
                    daemon.register(info)?; Some(daemon)
                } else { None };
                store.audit(serde_json::json!({"type":"server_start","bind":bind.to_string(),"pairing":pairing}))?;
                println!("Agent {} : wss://{bind}:{port}/v1/ws\nEmpreinte : {}\nAppairage : {}", config.name, config.agent_id, if pairing { "ouvert 120 secondes" } else { "fermé" });
                let app = App { store, config, pairing_until:pairing.then(|| Instant::now()+Duration::from_secs(120)), slots:Arc::new(tokio::sync::Semaphore::new(8)) };
                let handle = axum_server::Handle::new();
                let stop = handle.clone();
                tokio::spawn(async move { let _ = tokio::signal::ctrl_c().await; stop.graceful_shutdown(Some(Duration::from_secs(3))); });
                axum_server::bind_rustls(SocketAddr::new(bind, port), tls).handle(handle).serve(server::router(app).into_make_service()).await?;
                Ok::<_, anyhow::Error>(())
            })?;
        }
    }
    Ok(())
}
