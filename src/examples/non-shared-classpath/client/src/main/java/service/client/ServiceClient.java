package service.client;

import org.nustaq.kontraktor.Actor;
import org.nustaq.kontraktor.IPromise;
import org.nustaq.kontraktor.remoting.tcp.TCPClientConnector;
import static service.common.MyProtocol.*;
import service.common.MyService;

/**
 * Created by ruedi on 19/05/15.
 *
 * Client is not made an actor for simplicity
 * (and to demonstrate you don't need to move completely to actor-style in order
 * to make use of kkontraktor remoting)
 *
 */
public class ServiceClient {

    public void run() {
        MyService service = (MyService) TCPClientConnector.Connect(MyService.class, "localhost", 6789, (connector, error) -> {
            System.out.println("Disconnected from service .. exiting");
            System.exit(0);
        }).await();

        service.$addPerson( new Person("Hickory","Heinz",13,Sex.MALE)) ;
        service.$addPerson( new Person("Blob","Jim",13,Sex.MALE)) ;
        service.$addPerson( new Person("Gerstenbroich-Huckerbühl","Mareike",17,Sex.MALE)) ;

        service.$listPersons(null,null,13, (p,err) -> {
            if ( ! Actor.isFinal(err) )
                System.out.println("found: "+p);
            else {
                System.out.println("query finished");
                System.exit(0);
            }
        });
    }

    public static void main( String a[] ) {
        new ServiceClient().run();
    }

}