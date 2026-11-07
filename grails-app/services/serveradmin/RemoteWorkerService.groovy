package serveradmin

import grails.gorm.transactions.Transactional
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID

@Transactional
class RemoteWorkerService {
    static final Set<String> ALLOWED = ['ALL_JOBS','FREECAD','CAD_TO_GLB','CAD_TO_STEP','GENERATE_BOM','CAD_PROPERTIES','FILE_PROCESSING'] as Set

    RemoteWorker register(Map payload) {
        String key = payload.publicKey?.toString()
        String name = payload.name?.toString()?.trim()
        if (!name || name.length() > 120 || !key || key.length() > 256) throw new IllegalArgumentException('Invalid worker registration')
        byte[] encoded = Base64.decoder.decode(key)
        if (encoded.length > 100) throw new IllegalArgumentException('Invalid public key')
        KeyFactory.getInstance('Ed25519').generatePublic(new X509EncodedKeySpec(encoded))
        String fingerprint = MessageDigest.getInstance('SHA-256').digest(encoded).encodeHex().toString()
        RemoteWorker existing = RemoteWorker.findByPublicKeyFingerprint(fingerprint)
        if (existing) return existing // no re-approval on key reuse
        List caps = (payload.capabilities instanceof List) ? payload.capabilities : []
        if (caps.size() > 30 || caps.any { !(it instanceof String) || !(it in ALLOWED) }) throw new IllegalArgumentException('Invalid capabilities')
        new RemoteWorker(workerId: UUID.randomUUID().toString(), workerName: name,
            publicKeyBase64: key, publicKeyFingerprint: fingerprint,
            capabilities: JsonOutput.toJson(caps.unique()),
            maxConcurrentJobs: Math.min(16, Math.max(1, (payload.maxConcurrentJobs ?: 1) as Integer))).save(failOnError:true, flush:true)
    }

    RemoteWorker verifyHeartbeat(String id, String timestamp, String nonce, String signatureB64) {
        RemoteWorker worker = RemoteWorker.findByWorkerId(id)
        if (!worker || worker.status != 'APPROVED') throw new SecurityException('Worker not approved')
        long ts
        try { ts = Long.parseLong(timestamp) } catch (Exception ignored) { throw new SecurityException('Invalid timestamp') }
        if (Math.abs(System.currentTimeMillis() / 1000L - ts) > 120L) throw new SecurityException('Expired request')
        if (!nonce || nonce.length() > 100 || nonce == worker.lastNonce) throw new SecurityException('Invalid nonce')
        byte[] sig = Base64.decoder.decode(signatureB64 ?: '')
        Signature verifier = Signature.getInstance('Ed25519')
        verifier.initVerify(KeyFactory.getInstance('Ed25519').generatePublic(new X509EncodedKeySpec(Base64.decoder.decode(worker.publicKeyBase64))))
        verifier.update("${id}:${timestamp}:${nonce}:heartbeat".getBytes('UTF-8'))
        if (!verifier.verify(sig)) throw new SecurityException('Invalid signature')
        worker.lastNonce = nonce
        worker.lastSeen = new Date()
        worker.save(failOnError:true, flush:true)
        worker
    }

    /** Authenticates one request and binds its signature to the operation and payload hash. */
    RemoteWorker verifyRequest(String id, String timestamp, String nonce, String signatureB64,
                               String operation, String payloadHash) {
        RemoteWorker w = RemoteWorker.findByWorkerId(id)
        if (!w || w.status != 'APPROVED') throw new SecurityException('Not approved')
        long ts = Long.parseLong(timestamp ?: '0')
        if (Math.abs(System.currentTimeMillis().intdiv(1000) - ts) > 120L) throw new SecurityException('Expired')
        if (!nonce || nonce.length() > 100 || w.lastNonce == nonce) throw new SecurityException('Replay')
        Signature verifier = Signature.getInstance('Ed25519')
        verifier.initVerify(KeyFactory.getInstance('Ed25519').generatePublic(new X509EncodedKeySpec(Base64.decoder.decode(w.publicKeyBase64))))
        verifier.update("${id}:${timestamp}:${nonce}:${operation}:${payloadHash}".getBytes('UTF-8'))
        if (!verifier.verify(Base64.decoder.decode(signatureB64 ?: ''))) throw new SecurityException('Signature invalid')
        w.lastNonce = nonce
        w.lastSeen = new Date()
        w.save(failOnError:true, flush:true)
        return w
    }

    void setStatus(Long id, String status) {
        if (!(status in ['APPROVED','REJECTED','REVOKED'])) throw new IllegalArgumentException('Invalid status')
        RemoteWorker w = RemoteWorker.get(id)
        if (!w) throw new IllegalArgumentException('Worker not found')
        if (w.status == 'REVOKED' && status == 'APPROVED') throw new IllegalArgumentException('Revoked identity must register with a new key')
        w.status = status
        w.save(failOnError:true, flush:true)
    }
}
