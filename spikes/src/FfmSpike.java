import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.*;
public class FfmSpike {
    public static void main(String[] a) throws Throwable {
        // C-10 sub-claim: is fresh Arena allocation zero-initialised on this JDK?
        try(Arena ar=Arena.ofConfined()){ MemorySegment s=ar.allocate(256); boolean allZero=true; for(int i=0;i<256;i++) if(s.get(ValueLayout.JAVA_BYTE,i)!=0) allZero=false; System.out.println("FRESH_ARENA_ALL_ZERO="+allZero);
            s.fill((byte)0x41); s.fill((byte)0); boolean wiped=true; for(int i=0;i<256;i++) if(s.get(ValueLayout.JAVA_BYTE,i)!=0) wiped=false; System.out.println("FILL_ZERO_WORKS="+wiped);
            // H5: mlock + madvise via downcalls
            Linker lk=Linker.nativeLinker(); SymbolLookup libc=lk.defaultLookup();
            MethodHandle mlock=lk.downcallHandle(libc.find("mlock").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            int rc=(int)mlock.invoke(s.address()==0? s : s, 256L); System.out.println("MLOCK_RC="+rc);
            MethodHandle madvise=lk.downcallHandle(libc.find("madvise").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
            int rc2=(int)madvise.invoke(s, 256L, 16 /*MADV_DONTDUMP on Linux*/); System.out.println("MADVISE_DONTDUMP_RC="+rc2);
        }
        String limits=Files.readString(Path.of("/proc/self/limits")); for(String l: limits.split("\n")) if(l.contains("locked memory")) System.out.println("LIMIT: "+l.trim());
    }
}
