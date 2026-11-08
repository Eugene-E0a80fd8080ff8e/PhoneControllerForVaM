package local.movementtrackerforvam.movementtrackerforvam;
import org.junit.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
public class LatestWorkerTest {
 @Test public void slowDecodeKeepsOnlyNewestPendingFrame() throws Exception {
  LatestWorker worker=new LatestWorker("test decoder");
  CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1),done=new CountDownLatch(1);
  AtomicInteger result=new AtomicInteger();
  try{
   worker.submit(()->{started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
   assertTrue(started.await(2,TimeUnit.SECONDS));
   worker.submit(()->result.set(1));
   worker.submit(()->{result.set(2);done.countDown();});
   release.countDown();assertTrue(done.await(2,TimeUnit.SECONDS));assertEquals(2,result.get());
  }finally{release.countDown();worker.close();}
 }
 @Test public void closeDiscardsPendingWorkWithoutWaitingForDecode() throws Exception {
  LatestWorker worker=new LatestWorker("test shutdown");
  CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1),exited=new CountDownLatch(1);
  AtomicInteger queued=new AtomicInteger();
  try{
   worker.submit(()->{started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}exited.countDown();});
   assertTrue(started.await(2,TimeUnit.SECONDS));worker.submit(queued::incrementAndGet);
   worker.close();release.countDown();assertTrue(exited.await(2,TimeUnit.SECONDS));assertEquals(0,queued.get());
  }finally{release.countDown();worker.close();}
 }
}
