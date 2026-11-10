import java.security.SecureRandom;
import java.util.*;
/** Batch victim for experiments: one JVM per cell, N trials, parent scans between READY and GO.
 *  Protocol (stdout): "PATTERN <hex>" then "READY"; waits for one stdin line per trial. */
public class ExperimentVictim {
    static volatile long blackhole;
    public static void main(String[] a) throws Exception {
        String method=a[0]; int n=Integer.parseInt(a[1]);
        Scanner in=new Scanner(System.in); SecureRandom rnd=new SecureRandom();
        for(int i=0;i<n;i++){
            // Pattern is generated INSIDE doTrial and only its hex encoding leaves that frame.
            // (First version held the raw pattern in main's frame -> scanner found it in every
            //  trial regardless of wiping. Caught because Serial/fill impossibly read 200/200.)
            String hex=doTrial(method, rnd);
            System.out.println("PATTERN "+hex); System.out.flush();
            System.out.println("READY"); System.out.flush();
            if(!in.hasNextLine()) break; in.nextLine();
        }
        System.out.println("DONE"); System.out.flush();
    }
    static String doTrial(String method, SecureRandom rnd){
        // The secret IS the pattern: no clone (v2 cloned it and wiped only the clone,
        // leaving the identical original as unreachable garbage -> found in every trial).
        byte[] secret=new byte[32]; rnd.nextBytes(secret);
        String hex=HexFormat.of().formatHex(secret); // hex encoding != raw bytes
        // promote: survive a GC so the array is not purely eden-fresh
        byte[][] churn=new byte[500][]; for(int j=0;j<churn.length;j++) churn[j]=new byte[512];
        System.gc(); blackhole+=secret[0]+churn[0][0];
        switch(method){
            case "drop": break; // baseline: reference dropped, no wipe ("delete only" analogue)
            case "fill": Arrays.fill(secret,(byte)0); break;
            case "multipass": for(int p=0;p<7;p++) Arrays.fill(secret,(byte)(p%2==0?0xFF:0x00)); break;
            case "fillgc": Arrays.fill(secret,(byte)0); secret=null; System.gc(); System.gc(); break;
            default: throw new IllegalArgumentException(method);
        }
        blackhole+= (secret==null?0:secret.length);
        return hex;
    }
}
