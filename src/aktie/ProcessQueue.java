package aktie;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Level;
import java.util.logging.Logger;

import aktie.data.CObj;
import aktie.index.CObjList;

public class ProcessQueue implements Runnable
{

    Logger log = Logger.getLogger ( "aktie" );

    public static int MAXQUEUESIZE = 500; //Long lists should be in CObjList each one could have open indexreader!

    private ConcurrentLinkedDeque<Object> queue;
    private BatchProcessor processor;
    private boolean stop;

    private String name;

    public ProcessQueue ( String n )
    {
        queue = new ConcurrentLinkedDeque<Object>();
        processor = new BatchProcessor();
        name = n;
        Thread t = new Thread ( this, name );
        t.start();
    }

    public void addProcessor ( CObjProcessor p )
    {
        processor.addProcessor ( p );
    }

    private void process()
    {
        if ( !stop )
        {
            if ( log.isLoggable ( Level.INFO ) )
            {
                log.info ( "process " + name + " : " + queue.size() );
            }

            Object o = queue.poll();

            try
            {
                if ( o != null )
                {
                    if ( o instanceof CObj )
                    {
                        processCObj ( ( CObj ) o );
                    }

                    else if ( o instanceof CObjList )
                    {
                        CObjList cl = ( CObjList ) o;

                        for ( int c = 0; c < cl.size(); c++ )
                        {
                            processCObj ( cl.get ( c ) );
                        }

                        cl.close();
                    }

                    else
                    {
                        processor.processObj ( o );
                    }

                }

            }

            catch ( Exception e )
            {
                e.printStackTrace();
            }

        }

    }

    private void processCObj ( CObj o )
    {
        processor.processCObj ( o );
    }

    public synchronized void priorityEnqueue ( Object b )
    {
        queue.addFirst ( b );
        notifyAll();
    }

    public synchronized boolean enqueue ( Object b )
    {
        if ( queue.size() < MAXQUEUESIZE )
        {
            queue.add ( b );
            notifyAll();
            return true;
        }

        if ( log.isLoggable ( Level.INFO ) )
        {
            log.info ( "enqueue rejected. " + name + " size: " + queue.size() );
        }

        if ( b instanceof CObjList )
        {
            CObjList l = ( CObjList ) b;
            l.close();
        }

        return false;
    }

    public synchronized void stop()
    {
        stop = true;
        notifyAll();

        for ( Object o : queue )
        {
            if ( o instanceof CObjList )
            {
                CObjList l = ( CObjList ) o;
                l.close();
            }

        }

    }

    private synchronized void waitForData()
    {
        if ( queue.size() == 0 && !stop )
        {
            try
            {
                wait ( 5000L );
            }

            catch ( InterruptedException e )
            {
                e.printStackTrace();
            }

        }

    }

    public void run()
    {
        while ( !stop )
        {
            waitForData();
            process();
        }

    }

}
