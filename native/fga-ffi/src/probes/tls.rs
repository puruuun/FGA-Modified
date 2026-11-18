//! A full TLS 1.3 handshake between a rustls client and server over in-memory buffers, so the
//! probe needs no socket and no network permission.

use std::io::{Read, Write};
use std::sync::Arc;

use rustls::crypto::ring::default_provider;
use rustls::pki_types::{PrivateKeyDer, PrivatePkcs8KeyDer, ServerName};
use rustls::{
    ClientConfig, ClientConnection, ConnectionCommon, RootCertStore, ServerConfig, ServerConnection,
};

/// Handshake flights are few; a loop that runs this long is stuck.
const MAX_ROUNDS: usize = 16;

pub fn handshake_in_memory() -> Result<String, String> {
    let key_pair = rcgen::KeyPair::generate().map_err(|e| format!("key: {e}"))?;
    let cert = rcgen::CertificateParams::new(vec!["localhost".to_owned()])
        .and_then(|params| params.self_signed(&key_pair))
        .map_err(|e| format!("certificate: {e}"))?;
    let cert_der = cert.der().clone();
    let key_der = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key_pair.serialize_der()));

    let provider = Arc::new(default_provider());
    let server_config = ServerConfig::builder_with_provider(provider.clone())
        .with_protocol_versions(&[&rustls::version::TLS13])
        .map_err(|e| format!("server versions: {e}"))?
        .with_no_client_auth()
        .with_single_cert(vec![cert_der.clone()], key_der)
        .map_err(|e| format!("server cert: {e}"))?;

    let mut roots = RootCertStore::empty();
    roots.add(cert_der).map_err(|e| format!("root: {e}"))?;
    let client_config = ClientConfig::builder_with_provider(provider)
        .with_protocol_versions(&[&rustls::version::TLS13])
        .map_err(|e| format!("client versions: {e}"))?
        .with_root_certificates(roots)
        .with_no_client_auth();

    let server_name = ServerName::try_from("localhost").map_err(|e| format!("name: {e}"))?;
    let mut client = ClientConnection::new(Arc::new(client_config), server_name)
        .map_err(|e| format!("client: {e}"))?;
    let mut server =
        ServerConnection::new(Arc::new(server_config)).map_err(|e| format!("server: {e}"))?;

    let mut rounds = 0;
    while client.is_handshaking() || server.is_handshaking() {
        rounds += 1;
        if rounds > MAX_ROUNDS {
            return Err("handshake did not finish".to_owned());
        }
        pump(&mut client, &mut server).map_err(|e| format!("client->server: {e}"))?;
        pump(&mut server, &mut client).map_err(|e| format!("server->client: {e}"))?;
    }

    let version = client
        .protocol_version()
        .ok_or_else(|| "no protocol version".to_owned())?;
    let suite = client
        .negotiated_cipher_suite()
        .ok_or_else(|| "no cipher suite".to_owned())?
        .suite();

    send(&mut client, &mut server, b"hello from client").map_err(|e| format!("to server: {e}"))?;
    send(&mut server, &mut client, b"hello from server").map_err(|e| format!("to client: {e}"))?;

    Ok(format!(
        "{version:?} {suite:?} in {rounds} rounds, both messages intact"
    ))
}

/// Moves every pending TLS record from one side to the other and lets the receiver process it.
fn pump<A, B>(from: &mut ConnectionCommon<A>, to: &mut ConnectionCommon<B>) -> Result<(), String> {
    let mut wire = Vec::new();
    while from.wants_write() {
        from.write_tls(&mut wire).map_err(|e| e.to_string())?;
    }
    let mut pending = wire.as_slice();
    while !pending.is_empty() {
        to.read_tls(&mut pending).map_err(|e| e.to_string())?;
        to.process_new_packets().map_err(|e| e.to_string())?;
    }
    Ok(())
}

fn send<A, B>(
    from: &mut ConnectionCommon<A>,
    to: &mut ConnectionCommon<B>,
    message: &[u8],
) -> Result<(), String> {
    from.writer()
        .write_all(message)
        .map_err(|e| e.to_string())?;
    pump(from, to)?;
    let mut received = vec![0; message.len()];
    to.reader()
        .read_exact(&mut received)
        .map_err(|e| e.to_string())?;
    if received == message {
        Ok(())
    } else {
        Err("message changed in transit".to_owned())
    }
}
