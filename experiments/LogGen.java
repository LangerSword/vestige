import dev.jvd.log.*; import dev.jvd.evidence.*; import java.nio.file.*; import java.security.*; import java.time.Instant; import java.util.*;
/** Generates a template witnessed log dir for tamper experiments. Args: dir, nRecords */
public class LogGen {
    public static void main(String[] a) throws Exception {
        Path dir=Path.of(a[0]); int n=Integer.parseInt(a[1]); Files.createDirectories(dir);
        KeyPair logKp=dev.jvd.evidence.Signer.generateKeyPair(); KeyPair witKp=dev.jvd.evidence.Signer.generateKeyPair();
        PersistentLog pl=PersistentLog.open(dir);
        Instant base=Instant.parse("2026-11-10T00:00:00Z");
        for(int i=0;i<n;i++){
            EvidenceRecord r=new EvidenceRecord(); r.timestampUtc=base.plusSeconds(i*3600L);
            r.subjectHash=EvidenceRecord.subjectHash("salt","subject-"+i);
            dev.jvd.evidence.Signer.sign(r, logKp.getPrivate());
            pl.append(r.toSignedJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        Checkpoint cp=new Checkpoint(pl.log().size(), pl.log().rootHash(), System.currentTimeMillis()).sign(logKp.getPrivate());
        cp.witnessCosign(witKp.getPrivate(), witKp.getPublic(), logKp.getPublic());
        pl.saveCheckpoint(cp);
        Files.writeString(dir.resolve("pubkeys.txt"), Base64.getEncoder().encodeToString(logKp.getPublic().getEncoded())+"\n"+Base64.getEncoder().encodeToString(witKp.getPublic().getEncoded())+"\n");
        System.out.println("LOGGEN_OK size="+pl.log().size());
    }
}
