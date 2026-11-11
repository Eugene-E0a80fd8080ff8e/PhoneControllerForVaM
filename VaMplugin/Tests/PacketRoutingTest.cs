// Compile with the plugin sources and VaM/Unity references; not a plugin entry point.
using System;
using System.Linq;
using System.Text;
using MVRPlugin;
public static class PacketRoutingTest
{
    public static void Main()
    {
        byte[][] camera={Encoding.ASCII.GetBytes("PCCHELLO2"),Encoding.ASCII.GetBytes("PCCHELLO3"),Encoding.ASCII.GetBytes("PCP1"),Encoding.ASCII.GetBytes("PCP3"),Encoding.ASCII.GetBytes("PCP4"),Encoding.ASCII.GetBytes("PBA1")};
        foreach(byte[] packet in camera) if(!PhoneController.IsCameraPacket(packet))throw new Exception("Camera family was sent to legacy parser");
        byte[][] legacy={new byte[]{255},new byte[]{33,1,0},new byte[]{0},new byte[0],Encoding.ASCII.GetBytes("PCX1")};
        foreach(byte[] packet in legacy) if(PhoneController.IsCameraPacket(packet))throw new Exception("Legacy/unknown packet was intercepted");
        if(PhoneController.IsCameraPacket(null))throw new Exception("Null packet accepted");
        if(!PhoneController.IsControllerDiscovery(new byte[]{254}))throw new Exception("Controller probe not recognized");
        if(PhoneController.IsControllerDiscovery(new byte[]{255}) || PhoneController.IsControllerDiscovery(new byte[]{254,0}))throw new Exception("Beacon or malformed probe misclassified");
        int entries=typeof(PhoneController).Assembly.GetTypes().Count(t=>t!=typeof(MVRScript) && typeof(MVRScript).IsAssignableFrom(t));
        if(entries!=1)throw new Exception("Expected exactly one VaM plugin entry, got "+entries);
        Console.WriteLine("Packet routing and single plugin entry tests passed.");
    }
}
