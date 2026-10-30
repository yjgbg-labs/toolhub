import java.io.InputStream;
import java.io.OutputStream;
import java.sql.DriverManager;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.SandboxPolicy;
import groot.graaljs.GraalSdkBindings;
import scala.collection.immutable.Vector$;
import scala.jdk.javaapi.CollectionConverters;
import scala.runtime.BoxedUnit;

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
            var sdk = new GraalSdkBindings(context, Vector$.MODULE$.from(CollectionConverters.asScala(List.of("health"))),
                (id, input, resolve, reject) -> {
                    var response = context.getBindings("js").getMember("JSON").getMember("parse")
                        .execute("{\"status\":200,\"headers\":{},\"body\":\"ok\"}");
                    resolve.execute(response);
                    return BoxedUnit.UNIT;
                });
            sdk.install();
            context.eval("js", "tools.health().then(r => globalThis.sdkResult = r.body)");
            if (!context.eval("js", "sdkResult").asString().equals("ok")) throw new AssertionError("Command SDK failed");
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
