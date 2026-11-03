namespace Fixture;
public class Calculator
{
    public static int Main(string[] args)
    {
        Console.OutputEncoding = new System.Text.UTF8Encoding(false);
        if (args[0] == "args") {
            foreach (var value in args.Skip(1)) Console.WriteLine(Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes(value)));
            return 7;
        }
        if (args[0] == "stdin") { Console.Write(Console.In.ReadToEnd()); return 0; }
        if (args[0] == "hang") { Console.WriteLine("started"); Hang(); }
        if (args[0] == "spawn") {
            var dotnet = System.Diagnostics.Process.GetCurrentProcess().MainModule!.FileName;
            var assembly = typeof(Calculator).Assembly.Location;
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(dotnet, "\"" + assembly + "\" child \"" + args[1] + "\"") { UseShellExecute = false });
            Hang();
        }
        if (args[0] == "child") while (true) { File.AppendAllText(args[1], "x"); Thread.Sleep(50); }
        return 0;
    }
    public int Total { get; set; }
    public Calculator(int initial) { Total = initial; }
    public int Add(int value) => Total += value;
    public static int Sum(int a, int b) => a + b;
    public static long Large(long value) => value;
    public static string Echo(string value) { Console.WriteLine("fixture log: " + value); return value; }
    public static void Fail() => throw new InvalidOperationException("fixture failure");
    public static void Hang() => Thread.Sleep(Timeout.Infinite);
}
