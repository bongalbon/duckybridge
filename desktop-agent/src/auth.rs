use crate::{model::*, store::Store};
use anyhow::{bail, Context, Result};
use jsonwebtoken::{decode, decode_header, Algorithm, DecodingKey, Validation};
use std::collections::HashMap;

pub struct Authorizer {
    // Replay cache is confined to one socket, which also has a random audience.
    used: HashMap<String, u64>,
    pub audience: String,
    pub config: Config,
}
impl Authorizer {
    pub fn new(config: Config) -> Self {
        Self {
            used: HashMap::new(),
            audience: uuid::Uuid::new_v4().to_string(),
            config,
        }
    }
    pub fn verify(&mut self, token: &str, store: &Store) -> Result<(Claims, Device)> {
        let header = decode_header(token)?;
        if header.alg != Algorithm::ES256 {
            bail!("Algorithme refusé");
        }
        let id = header.kid.context("kid manquant")?;
        let device = {
            let _guard = store.lock()?;
            store
                .devices()?
                .into_iter()
                .find(|d| d.id == id && d.expires_at > now())
                .context("Appareil inconnu, révoqué ou expiré")?
        };
        let mut validation = Validation::new(Algorithm::ES256);
        validation.leeway = 0;
        validation.set_audience(&[&self.audience]);
        validation.set_issuer(&["duckybridge-v1"]);
        validation.set_required_spec_claims(&["exp", "iat", "sub", "aud", "iss", "jti"]);
        let claims = decode::<Claims>(
            token,
            &DecodingKey::from_ec_pem(device.public_key_pem.as_bytes())?,
            &validation,
        )?
        .claims;
        let time = now();
        if claims.v != 1
            || claims.sub != id
            || claims.iat > time + 5
            || claims.exp <= time
            || claims.exp <= claims.iat
            || claims.exp - claims.iat > 60
            || claims.iat + 65 < time
            || claims.jti.len() > 64
            || uuid::Uuid::parse_str(&claims.jti).is_err()
        {
            bail!("Requête invalide ou expirée");
        }
        self.used.retain(|_, exp| *exp > time);
        if self.used.len() >= 2048 || self.used.insert(claims.jti.clone(), claims.exp).is_some() {
            bail!("Rejeu ou trop de requêtes");
        }
        Ok((claims, device))
    }
}
