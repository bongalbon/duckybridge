use crate::model::*;
use anyhow::{bail, Context, Result};
use fs2::FileExt;
use p256::pkcs8::{DecodePublicKey, EncodePublicKey};
use serde::{de::DeserializeOwned, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, File, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
};

#[derive(Clone)]
pub struct Store(pub PathBuf);
impl Store {
    pub fn prepare(&self) -> Result<()> {
        fs::create_dir_all(&self.0)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&self.0, fs::Permissions::from_mode(0o700))?;
        }
        Ok(())
    }
    pub fn lock(&self) -> Result<File> {
        self.prepare()?;
        let f = OpenOptions::new()
            .create(true)
            .truncate(false)
            .read(true)
            .write(true)
            .open(self.0.join("state.lock"))?;
        f.lock_exclusive()?;
        Ok(f)
    }
    pub fn read<T: DeserializeOwned>(&self, name: &str) -> Result<T> {
        Ok(serde_json::from_slice(&fs::read(self.0.join(name))?)?)
    }
    pub fn write<T: Serialize>(&self, name: &str, value: &T) -> Result<()> {
        let mut tmp = tempfile::NamedTempFile::new_in(&self.0)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            tmp.as_file()
                .set_permissions(fs::Permissions::from_mode(0o600))?;
        }
        tmp.write_all(&serde_json::to_vec_pretty(value)?)?;
        tmp.as_file().sync_all()?;
        // tempfile persist atomically replaces the target on supported platforms.
        tmp.persist(self.0.join(name))?;
        Ok(())
    }
    pub fn devices(&self) -> Result<Vec<Device>> {
        self.read("devices.json")
    }
    pub fn audit(&self, event: serde_json::Value) -> Result<()> {
        let _guard = self.lock()?;
        let mut f = OpenOptions::new()
            .create(true)
            .append(true)
            .open(self.0.join("audit.jsonl"))?;
        writeln!(f, "{}", serde_json::json!({"time":now(), "event":event}))?;
        f.sync_data()?;
        Ok(())
    }
    pub fn revoke(&self, id: &str) -> Result<()> {
        let _guard = self.lock()?;
        let mut devices = self.devices()?;
        devices.retain(|d| d.id != id);
        self.write("devices.json", &devices)
    }
}

pub fn fingerprint_pem(pem: &str) -> Result<String> {
    let key = p256::PublicKey::from_public_key_pem(pem).context("Clé publique P-256 invalide")?;
    Ok(hex::encode(Sha256::digest(
        key.to_public_key_der()?.as_bytes(),
    )))
}

pub fn init(store: &Store, name: &str, cwd: &Path) -> Result<()> {
    anyhow::ensure!(
        !name.trim().is_empty() && name.len() <= 80 && !name.chars().any(char::is_control),
        "Nom de l'agent invalide"
    );
    let _guard = store.lock()?;
    if store.0.join("config.json").exists() {
        bail!("Agent déjà initialisé");
    }
    let cwd = fs::canonicalize(cwd)?;
    anyhow::ensure!(
        cwd.is_dir(),
        "Le répertoire de travail doit être un dossier"
    );
    let rcgen::CertifiedKey { cert, key_pair } =
        rcgen::generate_simple_self_signed(vec!["duckybridge.local".into()])?;
    let id = hex::encode(Sha256::digest(cert.der()));
    let entry = keyring::Entry::new("DuckyBridge", &id)?;
    entry.set_password(&key_pair.serialize_pem()).context("Magasin sécurisé indisponible : aucune clé enregistrée sur disque. Ouvrez une session avec Credential Manager / Secret Service / Keychain.")?;
    fs::write(store.0.join("agent-cert.pem"), cert.pem())?;
    let mut commands = std::collections::BTreeMap::new();
    #[cfg(windows)]
    let system_dir = std::env::var("SystemRoot").unwrap_or_else(|_| "C:\\Windows".into());
    for alias in ["whoami", "hostname"] {
        #[cfg(windows)]
        let program = format!("{system_dir}\\System32\\{alias}.exe");
        #[cfg(not(windows))]
        let program = format!("/usr/bin/{alias}");
        commands.insert(
            alias.into(),
            AllowedCommand {
                program,
                args: vec![],
            },
        );
    }
    store.write("devices.json", &Vec::<Device>::new())?;
    store.write("pending.json", &Vec::<Device>::new())?;
    store.write(
        "config.json",
        &Config {
            agent_id: id,
            name: name.into(),
            cwd: cwd.to_string_lossy().into(),
            commands,
        },
    )?;
    Ok(())
}
