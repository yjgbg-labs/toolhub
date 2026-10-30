import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.SandboxPolicy;
import org.graalvm.polyglot.proxy.ProxyExecutable;

/** Verify the delivered assembly, including service descriptors and embedded native resources. */
public class AssemblySmoke {
    public static void main(String[] args) throws Exception {
        try (var engine = Engine.newBuilder("js")
                .sandbox(SandboxPolicy.ISOLATED).spawnIsolate(true)
                .out(OutputStream.nullOutputStream()).err(OutputStream.nullOutputStream())
                .option("engine.MaxIsolateMemory", "64MB").build();
             var context = Context.newBuilder("js").engine(engine).sandbox(SandboxPolicy.ISOLATED)
                .in(InputStream.nullInputStream()).out(OutputStream.nullOutputStream()).err(OutputStream.nullOutputStream())
                .option("sandbox.MaxHeapMemory", "4MB").option("sandbox.MaxCPUTime", "2s").build()) {
            if (context.eval("js", "6 * 7").asInt() != 42) throw new AssertionError("GraalJS evaluation failed");
            try (var sdk = AssemblySmoke.class.getResourceAsStream("/graaljs-sdk.js")) {
                if (sdk == null) throw new AssertionError("Missing command SDK resource");
                context.eval("js", new String(sdk.readAllBytes(), StandardCharsets.UTF_8))
                    .execute("[\"health\"]", (ProxyExecutable) call -> {
                        call[2].execute("{\"status\":200,\"headers\":{},\"body\":\"ok\"}");
                        return null;
                    });
                context.eval("js", "tools.health().then(r => globalThis.sdkResult = r.body)");
                if (!context.eval("js", "sdkResult").asString().equals("ok")) throw new AssertionError("Command SDK failed");
            }
        }
        Class.forName("org.sqlite.JDBC");
        try (var db = DriverManager.getConnection("jdbc:sqlite::memory:");
             var statement = db.createStatement();
             var rows = statement.executeQuery("SELECT 42")) {
            if (!rows.next() || rows.getInt(1) != 42) throw new AssertionError("SQLite query failed");
        }
        System.out.println("Assembly smoke passed: GraalJS isolate, command SDK and SQLite");
    }
}
