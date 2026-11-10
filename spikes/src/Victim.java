import java.util.*;
public class Victim {
    static volatile byte[] sink;
    static volatile long blackhole;
    static void stage(String s){ System.out.println("STAGE "+s); System.out.flush(); try{Thread.sleep(900);}catch(Exception e){} }
    public static void main(String[] a) throws Exception {
        String mode=a[0]; byte[] canary=HexFormat.of().parseHex(a[1]);
        switch(mode){
          case "hotloop": { // H1: hot method, wipe, drop
            for(int i=0;i<300_000;i++){ byte[] b=new byte[canary.length]; System.arraycopy(canary,0,b,0,canary.length); java.util.Arrays.fill(b,(byte)0); blackhole+=b.length; }
            stage("AFTER_HOTLOOP_WIPE_DROPPED"); break; }
          case "hotloop-nowipe": {
            for(int i=0;i<300_000;i++){ byte[] b=new byte[canary.length]; System.arraycopy(canary,0,b,0,canary.length); blackhole+=b[0]; }
            stage("AFTER_HOTLOOP_NOWIPE_DROPPED"); break; }
          case "gcstale": { // H2: live array copied by GC, then wiped+ dropped
            byte[] live=new byte[canary.length]; System.arraycopy(canary,0,live,0,canary.length); sink=live;
            stage("ALLOCATED");
            for(int i=0;i<5;i++){ byte[][] churn=new byte[2000][]; for(int j=0;j<churn.length;j++) churn[j]=new byte[1024]; System.gc(); }
            stage("AFTER_PROMOTION_GCS");
            java.util.Arrays.fill(live,(byte)0); sink=null;
            for(int i=0;i<3;i++){ System.gc(); }
            stage("AFTER_WIPE_DROP_GC"); break; }
          case "unreachable": { // H3: create in helper, return -> unreachable, no GC pressure
            make(canary); stage("UNREACHABLE_CREATED"); Thread.sleep(6000); stage("DONE"); break; }
          case "hold": { sink=canary.clone(); stage("HELD"); Thread.sleep(4000); break; }
          case "unreachable2": { makeRandom(a[2]); stage("UNREACHABLE_CREATED"); Thread.sleep(6000); stage("DONE"); break; }
        }
        System.out.println("VICTIM_EXIT"); System.out.flush();
    }
    static void makeRandom(String outPath) throws Exception { byte[] b=new byte[48]; new java.security.SecureRandom().nextBytes(b); java.nio.file.Files.writeString(java.nio.file.Path.of(outPath), HexFormat.of().formatHex(b)); blackhole+=b[0]; }
    static void make(byte[] c){ byte[] b=new byte[c.length]; System.arraycopy(c,0,b,0,c.length); blackhole+=b[0]; }
}
// appended marker (unused)
