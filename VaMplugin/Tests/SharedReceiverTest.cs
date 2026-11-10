// Standalone localhost integration harness, not included in the plugin .cslist.
using System;
using System.Net;
using System.Net.Sockets;
using System.Collections;
using System.Reflection;
using System.Runtime.Serialization;
using System.Threading;
using MVRPlugin;
class SharedReceiverTest {
 static void Main(){
  Type t=typeof(PhoneController);
  object instance=FormatterServices.GetUninitializedObject(t);
  BindingFlags f=BindingFlags.Instance|BindingFlags.NonPublic|BindingFlags.Public;
  t.GetField("socketLock",f).SetValue(instance,new object());
  Queue queue=new Queue();t.GetField("dataChannel",f).SetValue(instance,queue);
  Thread thread=new Thread(()=>t.GetMethod("TrFunc",f).Invoke(instance,null));thread.IsBackground=true;thread.Start();
  try{
   using(UdpClient client=new UdpClient(new IPEndPoint(IPAddress.Loopback,0))){
    client.Client.ReceiveTimeout=1500;
    IPEndPoint server=new IPEndPoint(IPAddress.Loopback,62701),from=new IPEndPoint(IPAddress.Any,0);
    byte[] reply=null;
    for(int attempt=0;attempt<3 && reply==null;attempt++){
     client.Send(new byte[]{254},1,server);
     try{reply=client.Receive(ref from);}catch(SocketException){}
    }
    if(reply==null||reply.Length!=1||reply[0]!=255||from.Port!=62701)throw new Exception("Unicast discovery failed; receiver error: "+t.GetField("receiverError",f).GetValue(instance));
    byte[] legacy={31,1,0,0,0};client.Send(legacy,legacy.Length,server);
    bool found=false;
    for(int attempt=0;attempt<50&&!found;attempt++){
     Thread.Sleep(10);lock(queue.SyncRoot)foreach(byte[] p in queue)if(p.Length==5&&p[0]==31&&p[1]==1)found=true;
    }
    if(!found)throw new Exception("Legacy command failed to reach body queue");
    Console.WriteLine("Shared receiver: direct discovery reply and legacy body queue delivery passed.");
   }
  }finally{t.GetMethod("StopReceiver",f).Invoke(instance,null);if(!thread.Join(2000))throw new Exception("Receiver failed to stop");}
 }
}
