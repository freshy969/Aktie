package aktie.net;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bouncycastle.crypto.digests.RIPEMD256Digest;
import org.hibernate.Query;
import org.hibernate.Session;
import org.json.JSONObject;

import aktie.ProcessQueue;
import aktie.UpdateCallback;
import aktie.WaitForProcess;
import aktie.crypto.Utils;
import aktie.data.CObj;
import aktie.data.HH2Session;
import aktie.data.RequestFile;
import aktie.index.CObjList;
import aktie.index.Index;
import aktie.json.CleanParser;
import aktie.user.IdentityManager;
import aktie.user.RequestFileHandler;

public class ConnectionThread implements Runnable, UpdateCallback
{
    Logger log = Logger.getLogger ( "aktie" );

    public static int MAXQUEUESIZE = 1000; //Long lists should be in CObjList each one could have open indexreader!
    public static long GUIUPDATEPERIOD = 10L * 1000L; // 10 seconds
    public static long MINGLOBALSEQDELAY = 1L * 60L * 1000L; //1 minute.
    //This is added becuase of a bug in the streaming library.
    public static long MAXOUTRATE = ( 1024L * 1024L ) / 1000L; //1MB per second

    private boolean stop;
    private boolean fileOnly;
    private Connection con;
    private ProcessQueue preprocQueue;
    private ProcessQueue inputQueue;
    private ConcurrentLinkedQueue<CObj> inQueue;
    private ConcurrentLinkedQueue<Object> outqueue;
    private Set<String> fillList;
    private Map<Long, Set<String>> pubReqdigs;
    private Map<Long, Set<String>> memReqdigs;
    private Map<Long, Set<String>> subReqdigs;
    private Map<String, Map<Long, Set<String>>> comReqdigs;
    private GetSendData2 conMan;
    private OutputProcessor outproc;
    private CObj endDestination;
    private DestinationThread dest;
    private Index index;
    private HH2Session session;
    private UpdateCallback guicallback;
    private OutputStream outstream;
    private RequestFileHandler fileHandler;
    private int listCount;
    private Set<String> accumulateTypes; //Types to combine in list before processing
    private List<CObj> currentList;
    private long inBytes;
    private long inNonFileBytes;
    private long outBytes;
    private ConnectionListener conListener;
    private ConnectionThread This;
    private IdentityManager IdentManager;
    private long lastMyRequest;
    private long startTime;
    private Set<String> memberships;
    private Set<String> subs;
    private Set<String> chkMemberships;
    private Set<String> chkSubs;
    private Set<RequestFile> filesHasRequested;
    private long lastFileUpdate = Long.MIN_VALUE;
    private String fileUp;
    private String fileDown;
    private File tmpDir;
    private ProcessQueue downloadQueue;

    public ConnectionThread ( DestinationThread d, HH2Session s, Index i, Connection c,
                              GetSendData2 sd, UpdateCallback cb, ConnectionListener cl, RequestFileHandler rf,
                              boolean fo, ProcessQueue preq, ProcessQueue inq, ProcessQueue dl )
    {
        This = this;
        downloadQueue = dl;
        preprocQueue = preq;
        inputQueue = inq;
        fileOnly = fo;
        conListener = cl;
        guicallback = cb;
        conMan = sd;
        con = c;
        dest = d;
        index = i;
        session = s;
        fileHandler = rf;
        fillList = new CopyOnWriteArraySet<String>();
        pubReqdigs = new ConcurrentHashMap<Long, Set<String>>();
        memReqdigs = new ConcurrentHashMap<Long, Set<String>>();
        subReqdigs = new ConcurrentHashMap<Long, Set<String>>();
        comReqdigs = new ConcurrentHashMap<String, Map<Long, Set<String>>>();
        subs = new CopyOnWriteArraySet<String>();
        memberships = new CopyOnWriteArraySet<String>();
        chkSubs = new CopyOnWriteArraySet<String>();
        chkMemberships = new CopyOnWriteArraySet<String>();
        filesHasRequested = new CopyOnWriteArraySet<RequestFile>();
        lastMyRequest = System.currentTimeMillis();
        startTime = lastMyRequest;
        IdentManager = new IdentityManager ( session, index );
        outqueue = new ConcurrentLinkedQueue<Object>();
        inQueue = new ConcurrentLinkedQueue<CObj>();
        accumulateTypes = new HashSet<String>();
        accumulateTypes.add ( CObj.FRAGMENT );


        outproc = new OutputProcessor();
        Thread t = new Thread ( this, "Input Connection Process Thread" );
        t.start();
    }

    public void setTempDir ( File tmp )
    {
        tmpDir = tmp;
    }

    public void setFileMode ( boolean filemode )
    {
        fileOnly = filemode;
    }

    public boolean isFileMode()
    {
        return fileOnly;
    }

    public long getStartTime()
    {
        return startTime;
    }

    public long getInBytes()
    {
        return inBytes;
    }

    public long getOutBytes()
    {
        return outBytes;
    }

    public CObj getEndDestination()
    {
        return endDestination;
    }

    public DestinationThread getLocalDestination()
    {
        return dest;
    }

    boolean updatemembers = false;
    boolean updatesubs = false;
    public void addChkMem ( String d )
    {
        chkMemberships.add ( d );
    }

    public void addChkSub ( String d )
    {
        chkSubs.add ( d );
    }

    public void addReqDig ( String d )
    {
        fillList.add ( d );
    }

    public void digestDone ( String dg )
    {
        if ( doLog() )
        {
            appendInput ( "digestDone: " + dg );
        }

        for ( Iterator<Entry<String, Map<Long, Set<String>>>> i = comReqdigs.entrySet().iterator(); i.hasNext(); )
        {
            Entry<String, Map<Long, Set<String>>> e = i.next();
            Map<Long, Set<String>> mp = e.getValue();
            String comid = e.getKey();

            for ( Iterator<Entry<Long, Set<String>>> i2 = mp.entrySet().iterator(); i2.hasNext(); )
            {
                Entry<Long, Set<String>> e2 = i2.next();
                Set<String> ds = e2.getValue();
                long seqnum = e2.getKey();

                if ( ds.remove ( dg ) )
                {
                    if ( doLog() )
                    {
                        appendInput ( "Removing from community list for seqnum: " + seqnum + " comid: " + comid + " ds: " + ds.size() );
                    }

                    if ( ds.size() == 0 )
                    {
                        if ( doLog() )
                        {
                            appendInput ( "Community sequence number complete: " + seqnum + " comid: " + comid );
                        }

                        i2.remove();
                        //Go update the sequence for this id
                        IdentManager.updateIdentityCommunitySeqNumber (
                            getEndDestination().getId(), comid, seqnum, false );
                    }

                }

            }

            if ( mp.size() == 0 )
            {
                i.remove();
            }

        }

        for ( Iterator<Entry<Long, Set<String>>> i = pubReqdigs.entrySet().iterator(); i.hasNext(); )
        {
            Entry<Long, Set<String>> e = i.next();
            Set<String> ss = e.getValue();

            if ( ss.remove ( dg ) )
            {
                if ( doLog() )
                {
                    appendInput ( "Removing from global list for seqnum: " + e.getKey() );
                }

                if ( ss.size() == 0 )
                {
                    if ( doLog() )
                    {
                        appendInput ( "Global sequence number complete: " + e.getKey() );
                    }

                    IdentManager.updateGlobalSequenceNumber (
                        getEndDestination().getId(),
                        true, e.getKey(),
                        false, 0L,
                        false, 0L );
                    i.remove();
                }

            }

        }

        if ( doLog() )
        {
            appendInput ( "Check memReqdigs: " + memReqdigs + " done: " + dg );
        }

        for ( Iterator<Entry<Long, Set<String>>> i = memReqdigs.entrySet().iterator(); i.hasNext(); )
        {
            Entry<Long, Set<String>> e = i.next();
            Set<String> ss = e.getValue();

            if ( ss.remove ( dg ) )
            {
                if ( doLog() )
                {
                    appendInput ( "Removing from member list for seqnum: " + e.getKey() +
                                  " size: " + ss.size() + " upmem: " + updatemembers );
                }

                if ( ss.size() == 0 && updatemembers )
                {
                    if ( doLog() )
                    {
                        appendInput ( "Global sequence number complete " + e.getKey() );
                    }

                    IdentManager.updateGlobalSequenceNumber (
                        getEndDestination().getId(),
                        false, 0L,
                        true, e.getKey(),
                        false, 0L );
                    i.remove();
                }

            }

        }

        for ( Iterator<Entry<Long, Set<String>>> i = subReqdigs.entrySet().iterator(); i.hasNext(); )
        {
            Entry<Long, Set<String>> e = i.next();
            Set<String> ss = e.getValue();

            if ( ss.remove ( dg ) )
            {
                if ( ss.size() == 0 && updatesubs )
                {
                    IdentManager.updateGlobalSequenceNumber (
                        getEndDestination().getId(),
                        false, 0L,
                        false, 0L,
                        true, e.getKey() );
                    i.remove();
                }

            }

        }

    }

    public void checkDone()
    {
        updatemembers = ( chkMemberships.equals ( memberships ) );
        updatesubs = updatemembers && ( chkSubs.equals ( subs ) );

        if ( doLog() )
        {
            log.info ( "CHECK DONE: ME: " + getLocalDestination().getIdentity().getId() +
                       " FROM: " + getEndDestination().getId() + " " + memberships + " " + subs +
                       " " + updatemembers + " " + updatesubs );
        }

        chkMemberships.clear();
        chkSubs.clear();
    }

    public void setLastComSeq ( String comid, long ps )
    {
        if ( fillList.size() == 0 )
        {
            IdentManager.updateIdentityCommunitySeqNumber (
                getEndDestination().getId(), comid, ps, false );
            return;
        }

        CObjList rlst = new CObjList();

        for ( String n : fillList )
        {
            CObj r = new CObj();
            r.setType ( CObj.CON_REQ_DIG );
            r.setDig ( n );
            rlst.add ( r );
        }

        Set<String> cs = new CopyOnWriteArraySet<String>();
        cs.addAll ( fillList );
        Map<Long, Set<String>> comset = comReqdigs.get ( comid );

        if ( comset == null )
        {
            comset = new ConcurrentHashMap<Long, Set<String>>();
            comReqdigs.put ( comid, comset );
        }

        comset.put ( ps, cs );

        fillList = new CopyOnWriteArraySet<String>();

        enqueue ( rlst );
    }

    public void setLastSeq ( boolean pb, long ps, boolean mb, long ms, boolean sb, long ss )
    {
        if ( doLog() )
        {
            appendInput ( "setLastSeq: pb: " + pb + " ps: "
                          + ps + " mb: " + mb
                          + " updatemembers: " + updatemembers
                          + " ms: " + ms + " sb: " + sb
                          + " updatesubs: " + updatesubs + " ss:" + ss +
                          " filllst: " + fillList );
        }

        if ( fillList.size() == 0 )
        {
            IdentManager.updateGlobalSequenceNumber (
                getEndDestination().getId(),
                pb, ps,
                updatemembers && mb, ms,
                updatesubs && sb, ss );
            return;
        }

        CObjList rlst = new CObjList();

        for ( String n : fillList )
        {
            CObj r = new CObj();
            r.setType ( CObj.CON_REQ_DIG );
            r.setDig ( n );
            rlst.add ( r );
        }

        if ( pb )
        {
            pubReqdigs.put ( ps, fillList );
        }

        if ( mb )
        {
            memReqdigs.put ( ms, fillList );
        }

        if ( sb )
        {
            subReqdigs.put ( ss, fillList );
        }

        fillList = new CopyOnWriteArraySet<String>();

        enqueue ( rlst );
    }

    private boolean updateMemberships()
    {
        boolean sendmems = false;

        if ( endDestination != null )
        {
            Set<String> nl0 = new HashSet<String>();
            Set<String> nl1 = new CopyOnWriteArraySet<String>();
            CObjList sl = index.getIdentityMemberships ( endDestination.getId() );

            if ( doLog() )
            {
                log.info ( "getIdentityMemberships: ME: " + getLocalDestination().getIdentity().getId() +
                           " FOR: (" +
                           endDestination.getId() + ") number: " + sl.size() );
            }

            for ( int c = 0; c < sl.size(); c++ )
            {
                try
                {
                    CObj sb = sl.get ( c );
                    String sbid = sb.getPrivate ( CObj.COMMUNITYID );

                    if ( sbid != null )
                    {
                        nl0.add ( sbid );
                    }

                }

                catch ( Exception e )
                {
                    e.printStackTrace();
                }

            }

            sl.close();

            sl = index.getIdentityPrivateCommunities ( endDestination.getId() );

            if ( doLog() )
            {
                log.info ( "getIdentityPrivateCommunities: ME: " + getLocalDestination().getIdentity().getId() +
                           " FOR: (" +
                           endDestination.getId() + ") number: " + sl.size() );
            }

            for ( int c = 0; c < sl.size(); c++ )
            {
                try
                {
                    CObj sb = sl.get ( c );
                    nl0.add ( sb.getDig() );
                }

                catch ( Exception e )
                {
                    e.printStackTrace();
                }

            }

            sl.close();

            sl = index.getIdentityMemberships ( getLocalDestination().getIdentity().getId() );

            if ( doLog() )
            {
                log.info ( "getIdentityMemberships: ME: (" + getLocalDestination().getIdentity().getId() +
                           ") FOR: " +
                           endDestination.getId() + " number: " + sl.size() );
            }

            for ( int c = 0; c < sl.size(); c++ )
            {
                try
                {
                    CObj sb = sl.get ( c );
                    String sbid = sb.getPrivate ( CObj.COMMUNITYID );

                    if ( nl0.contains ( sbid ) )
                    {
                        nl1.add ( sbid );
                    }

                }

                catch ( Exception e )
                {
                    e.printStackTrace();
                }

            }

            sl.close();

            sl = index.getIdentityPrivateCommunities ( getLocalDestination().getIdentity().getId() );

            if ( doLog() )
            {
                log.info ( "getIdentityPrivateCommunities: ME: (" + getLocalDestination().getIdentity().getId() +
                           ") FOR: " +
                           endDestination.getId() + " number: " + sl.size() );
            }

            for ( int c = 0; c < sl.size(); c++ )
            {
                try
                {
                    CObj sb = sl.get ( c );

                    if ( nl0.contains ( sb.getDig() ) )
                    {
                        nl1.add ( sb.getDig() );
                    }

                }

                catch ( Exception e )
                {
                    e.printStackTrace();
                }

            }

            sl.close();

            if ( !memberships.equals ( nl1 ) )
            {
                sendmems = true;
            }

            memberships = nl1;

            if ( doLog() )
            {
                log.info ( "memberships: " + memberships + " send: " + sendmems +
                           " ME: " + getLocalDestination().getIdentity().getId() +
                           " FOR: " +
                           endDestination.getId() + " number: " + sl.size() );
            }

        }

        return sendmems;
    }

    private boolean updateSubs()
    {
        boolean sendsubs = false;

        if ( endDestination != null )
        {
            Set<String> nl0 = new HashSet<String>();
            Set<String> nl1 = new CopyOnWriteArraySet<String>();
            CObjList sl = index.getMemberSubscriptions ( endDestination.getId() );

            for ( int c = 0; c < sl.size(); c++ )
            {
                try
                {
                    CObj sb = sl.get ( c );
                    String sbid = sb.getString ( CObj.COMMUNITYID );

                    if ( sbid != null )
                    {
                        nl0.add ( sbid );
                    }

                }

                catch ( Exception e )
                {
                    e.printStackTrace();
                }

            }

            sl.close();
            sl = index.getMemberSubscriptions ( getLocalDestination().getIdentity().getId() );

            for ( int c = 0; c < sl.size(); c++ )
            {
                try
                {
                    CObj sb = sl.get ( c );
                    String sbid = sb.getString ( CObj.COMMUNITYID );

                    if ( nl0.contains ( sbid ) )
                    {
                        nl1.add ( sbid );
                    }

                }

                catch ( Exception e )
                {
                    e.printStackTrace();
                }

            }

            sl.close();

            if ( !subs.equals ( nl1 ) )
            {
                sendsubs = true;
            }

            subs = nl1;

        }

        return sendsubs;
    }

    private boolean sendFirstMemSubs = true;
    private void updateSubsAndFiles()
    {
        long nu = conMan.getLastFileUpdate();

        //log.info("CON UPDATE SUBS AND FILES " + nu + " > " + lastFileUpdate);
        if ( doLog() )
        {
            appendOutput ( "updateSubsAndFiles: nu: " + nu + " > " + lastFileUpdate );
        }

        if ( endDestination != null && ( nu > lastFileUpdate || sendFirstMemSubs ) )
        {
            lastFileUpdate = nu;
            boolean sendm = updateMemberships();
            boolean sends = updateSubs();

            if ( sendm || sends || sendFirstMemSubs )
            {
                sendFirstMemSubs = false;
                sendMemsAndSubs();
            }

            if ( doLog() )
            {
                appendOutput ( "updateSubsAndFiles: subs: " + subs );
            }

            if ( fileOnly && endDestination != null )
            {
                filesHasRequested = conMan.getHasFileForConnection ( endDestination.getId(), subs );

                if ( doLog() )
                {
                    appendOutput ( "updateSubsAndFiles: filesHasRequested: " + filesHasRequested );
                }

            }

        }

    }

    private void sendCheck ( String t, String d )
    {
        CObj s = new CObj();
        s.setType ( t );
        s.setDig ( d );

        if ( doLog() )
        {
            String chk = "CHECKMEM";

            if ( CObj.CHECKSUB.equals ( t ) )
            {
                chk = "CHECKSUB";
            }

            if ( CObj.CHECKCOMP.equals ( t ) )
            {
                chk = "CHECKCOMP";
            }

            log.info ( "SENDING " + chk + " FROM: " + getLocalDestination().getIdentity().getId() +
                       " TO: " + this.getEndDestination().getId() + " DIG: " + d );
        }

        enqueue ( s );
    }

    private void sendMemsAndSubs()
    {
        for ( String d : memberships )
        {
            sendCheck ( CObj.CHECKMEM, d );
        }

        for ( String d : subs )
        {
            sendCheck ( CObj.CHECKSUB, d );
        }

        CObj c = new CObj();
        c.setType ( CObj.CHECKCOMP );
        enqueue ( c );
    }

    public void setEndDestination ( CObj o )
    {
        endDestination = o;
        updateSubsAndFiles();
    }

    public void stop()
    {
        if ( doLog() )
        {
            log.info ( "Closing connection ME: " +
                       getLocalDestination() + " to: " + getEndDestination() );
        }

        boolean wasstopped = stop;
        stop = true;
        outproc.go();
        dest.connectionClosed ( this );

        if ( con != null )
        {
            con.close();
        }

        if ( !wasstopped )
        {
            CObj endd = getEndDestination();

            if ( endd != null )
            {
                IdentManager.connectionClose ( endd.getId(),
                                               getInNonFileBytes(),
                                               getInBytes(), getOutBytes() );
            }

            if ( intrace != null )
            {
                try
                {
                    appendInput ( "stopping" );
                    intrace.close();
                }

                catch ( Exception e )
                {
                    e.printStackTrace();
                }

            }

            if ( outtrace != null )
            {
                try
                {
                    appendOutput ( "stopping" );
                    outtrace.close();
                }

                catch ( Exception e )
                {
                    e.printStackTrace();
                }

            }

            conListener.closed ( this );
        }

    }

    public boolean isStopped()
    {
        return stop;
    }

    public boolean enqueueRemoteRequest ( CObj o )
    {
        if ( inQueue.size() < MAXQUEUESIZE )
        {
            inQueue.add ( o );
            outproc.go();
            return true;
        }

        return false;
    }

    private boolean checkType ( Object o )
    {
        if ( fileOnly )
        {
            if ( o instanceof CObj )
            {
                CObj c = ( CObj ) o;
                String tt = c.getType();

                if ( ! ( CObj.CON_REQ_FRAG.equals ( tt ) ||
                         CObj.CON_REQ_FRAGLIST.equals ( tt ) ||
                         CObj.FILEF.equals ( tt ) ||
                         CObj.FRAGMENT.equals ( tt ) ||
                         CObj.IDENTITY.equals ( tt ) ||
                         CObj.CON_CHALLENGE.equals ( tt ) ||
                         CObj.CON_REPLY.equals ( tt ) ||
                         CObj.CON_FILEMODE.equals ( tt ) ||
                         CObj.FRAGFAILED.equals ( tt ) ||
                         CObj.SEQCOMP.equals ( tt ) ||
                         CObj.CHECKCOMP.equals ( tt ) ||
                         CObj.CHECKMEM.equals ( tt ) ||
                         CObj.CHECKSUB.equals ( tt )
                       ) )
                {
                    log.info ( "ERROR: file mode unacceptable type: " + tt );
                    return false;
                }

            }

        }

        return true;
    }

    public boolean enqueue ( Object o )
    {

        if ( outqueue.size() < MAXQUEUESIZE )
        {
            if ( checkType ( o ) )
            {
                outqueue.add ( o );
                outproc.go();
                return true;
            }

        }

        if ( doLog() )
        {
            log.info ( "CONTHREAD: DROPPED! " + o );
        }

        if ( o instanceof CObjList )
        {
            CObjList l = ( CObjList ) o;
            l.close();
        }

        return false;
    }

    public int getLength()
    {
        return length;
    }

    public void setLength ( int length )
    {
        this.length = length;
    }

    public void poke()
    {
        updatesubs = false;
        updatemembers = false;
        pubReqdigs.clear();
        memReqdigs.clear();
        subReqdigs.clear();

        sendFirstMemSubs = true;

        if ( outproc != null )
        {
            outproc.go();
        }

    }

    public void setLoadFile ( boolean loadFile )
    {
        this.loadFile = loadFile;
    }

    public void decrFileRequest()
    {
        if ( outproc != null )
        {
            outproc.decrFileRequests();
        }

    }

    private void process()
    {
        CObj o = inQueue.poll();

        try
        {
            if ( o != null )
            {
                WaitForProcess.waitForProcess ( this, o, inputQueue );
                //inProcessor.processCObj ( o );
            }

        }

        catch ( Exception e )
        {
            e.printStackTrace();
            stop();
        }

    }

    private class OutputProcessor implements Runnable
    {
        private int tick = 0;
        private long lastGlobalReq = 0;

        public synchronized void go()
        {
            notifyAll();
        }

        private synchronized void doWait()
        {
            if ( inQueue.size() == 0 && outqueue.size() == 0 )
            {
                try
                {
                    wait ( MINGLOBALSEQDELAY );
                }

                catch ( InterruptedException e )
                {
                }

            }

        }

        private Object getOutQueueData()
        {
            Object r = outqueue.poll();

            if ( r == null )
            {
                //Ok, process some of the requests from the other node.
                //Do this as late as possible so we don't have a bunch
                //of request data piling up waiting to go back.
                process();
                r = outqueue.poll();
            }

            return r;
        }

        public synchronized void incrFileRequests()
        {
            pendingFileRequests++;
        }

        public synchronized void decrFileRequests()
        {
            if ( pendingFileRequests > 0 )
            {
                pendingFileRequests--;
            }

        }

        private Object getLocalRequests()
        {
            if ( doLog() )
            {
                appendOutput ( "getLocalRequests: START pending: " + pendingFileRequests +
                               " dest: " + dest + " endDest: " + endDestination );
            }

            if (
                dest != null && endDestination != null &&
                (   pendingFileRequests < MAX_PENDING_FILES
                )
            )
            {
                if ( doLog() )
                {
                    appendOutput ( "getLocalRequests: fileOnly? " + fileOnly + " filesHasRequested? " + filesHasRequested );
                }

                if ( fileOnly && filesHasRequested != null )
                {
                    Object r = conMan.nextFile ( dest.getIdentity().getId(),
                                                 endDestination.getId(), filesHasRequested );

                    if ( doLog() )
                    {
                        appendOutput ( "getLocalRequests: nextFile? " + fileOnly + " filesHasRequested? " + filesHasRequested + " R: " + r );
                    }

                    if ( r != null )
                    {
                        if ( r instanceof CObj )
                        {
                            CObj co = ( CObj ) r;

                            if ( CObj.CON_REQ_FRAG.equals ( co.getType() ) ||
                                    CObj.CON_REQ_FRAGLIST.equals ( co.getType() ) )
                            {
                                incrFileRequests();
                            }

                        }

                    }

                    return r;
                }

                else
                {
                    int memcnt = 0;

                    if ( memberships != null )
                    {
                        memcnt = memberships.size();
                    }

                    int subcnt = 0;

                    if ( subs != null )
                    {
                        subcnt = subs.size();
                    }

                    boolean reqgbl = false;
                    long curtime = System.currentTimeMillis();
                    curtime -= MINGLOBALSEQDELAY;

                    if ( curtime > lastGlobalReq && fillList.isEmpty() )
                    {
                        lastGlobalReq = System.currentTimeMillis();
                        reqgbl = true;
                    }

                    if ( doLog() )
                    {
                        appendOutput ( "nextNonFile mem: " + memberships + " " +
                                       memcnt + " subs: " + subs + " " + subcnt + " reqgbl: " + reqgbl + " size: " + fillList.size() );
                    }

                    Object r = conMan.nextNonFile ( dest.getIdentity().getId(),
                                                    endDestination.getId(),
                                                    memberships, subs, reqgbl );

                    if ( doLog() )
                    {
                        appendOutput ( "nextNonFile " + r );
                    }

                    return r;
                }

            }

            return null;
        }

        private Object getData()
        {
            Object r = null;

            //Try to make fair by alternating seeing if we have a request
            //to go out or if the other node has requested data to send
            //back first.
            if ( tick == 0 )
            {
                r = getOutQueueData();
                tick = 1;
            }

            else
            {
                r = getLocalRequests();
                tick = 0;
            }

            //Ok, now just do both in case the first one we picked above
            //had no data.
            if ( r == null )
            {
                r = getOutQueueData();
            }

            if ( r == null )
            {
                r = getLocalRequests();
            }

            return r;
        }

        private void sendCObj ( CObj c ) throws IOException
        {
            sendCObjNoFlush ( c );
            outstream.flush();
        }

        private long lastRateCheck = 0;
        private long lastOutBytes = 0;
        private void sendCObjNoFlush ( CObj c ) throws IOException
        {
            lastSent = c.getType();
            lastSentTime = System.currentTimeMillis();
            JSONObject ot = c.getJSON();
            String os = ot.toString();
            byte ob[] = os.getBytes ( "UTF-8" );
            outBytes += ob.length;
            conListener.bytesSent ( ob.length );
            outstream.write ( ob );

            //Bug in streaming code when connecting
            //to local destination on same router
            long ct = System.currentTimeMillis();
            long ratedf = outBytes - lastOutBytes;
            long timedf = ct - lastRateCheck;

            if ( timedf > 0 )
            {
                long rate = ratedf / timedf;

                if ( rate > MAXOUTRATE )
                {
                    lastOutBytes = outBytes;
                    lastRateCheck = ct;

                    if ( doLog() )
                    {
                        appendOutput ( "THROTTLING rate " + rate + " > " + MAXOUTRATE );
                    }

                    try
                    {
                        Thread.sleep ( 100 );
                    }

                    catch ( InterruptedException e )
                    {
                        e.printStackTrace();
                    }

                }

            }

        }

        private void seeIfUseless()
        {
            long curtime = System.currentTimeMillis();

            if ( !fileOnly )
            {
                long cuttime = curtime - ConnectionManager2.MAX_TIME_WITH_NO_REQUESTS;

                if ( lastMyRequest < cuttime )
                {
                    stop();

                }

            }

            long maxtime = curtime - ConnectionManager2.MAX_CONNECTION_TIME;

            if ( startTime < maxtime )
            {
                stop();
            }

        }

        @Override
        public void run()
        {
            while ( !stop )
            {
                try
                {

                    seeIfUseless();

                    Object o = getData();

                    if ( doLog() )
                    {
                        appendOutput ( "WAIT FOR DATA.. " + o );
                    }

                    updateSubsAndFiles();

                    if ( o == null )
                    {
                        outstream.flush();
                        doWait();
                    }

                    else
                    {
                        //checkType ( o ); //TODO: Remove

                        if ( o instanceof CObj )
                        {
                            CObj c = ( CObj ) o;

                            if ( doLog() )
                            {
                                appendOutput ( c.getType() + "=============" );
                                appendOutput ( "comid:   " + c.getString ( CObj.COMMUNITYID ) );
                                appendOutput ( "creator: " + c.getString ( CObj.CREATOR ) );
                                appendOutput ( "memid:   " + c.getString ( CObj.MEMBERID ) );
                                appendOutput ( "seqnum:  " + c.getNumber ( CObj.SEQNUM ) );
                                appendOutput ( "first:   " + c.getNumber ( CObj.FIRSTNUM ) );
                                appendOutput ( "wdig:    " + c.getString ( CObj.FILEDIGEST ) );
                                appendOutput ( "offset:  " + c.getNumber ( CObj.FRAGOFFSET ) );
                            }

                            sendCObjNoFlush ( c );

                            if ( CObj.FILEF.equals ( c.getType() ) )
                            {
                                String lfs = c.getPrivate ( CObj.LOCALFILE );
                                Long offset = c.getNumber ( CObj.FRAGOFFSET );
                                Long len = c.getNumber ( CObj.FRAGSIZE );

                                if ( doLog() )
                                {
                                    appendOutput ( "Sending file fragment: " + lfs +
                                                   " offset: " + offset + " len: " + len );
                                }

                                if ( lfs != null && offset != null && len != null )
                                {
                                    byte buf[] = new byte[4096];
                                    File lf = new File ( lfs );
                                    RandomAccessFile raf = new RandomAccessFile ( lf, "r" );
                                    raf.seek ( offset );
                                    long ridx = 0;

                                    while ( ridx < len )
                                    {
                                        int l = raf.read ( buf, 0, Math.min ( buf.length,
                                                                              ( int ) ( len - ridx ) ) );

                                        if ( l < 0 )
                                        {
                                            throw new IOException ( "Oops." );
                                        }

                                        if ( l > 0 )
                                        {
                                            outstream.write ( buf, 0, l );
                                            outBytes += l;
                                            ridx += l;
                                            conListener.bytesSent ( l );
                                        }

                                    }

                                    raf.close();
                                }

                            }

                        }

                        else if ( o instanceof CObjList )
                        {
                            CObjList cl = ( CObjList ) o;
                            int len = cl.size();

                            if ( doLog() )
                            {
                                appendOutput ( CObj.CON_LIST + "=============" );
                                appendOutput ( "size:    " + len );
                            }

                            CObj lo = new CObj();
                            lo.setType ( CObj.CON_LIST );
                            lo.pushNumber ( CObj.COUNT, len );
                            sendCObj ( lo );

                            for ( int c = 0; c < len; c++ )
                            {
                                CObj sco = cl.get ( c );

                                if ( doLog() )
                                {
                                    appendOutput ( sco.getType() + "=============" );
                                    appendOutput ( "comid:   " + sco.getString ( CObj.COMMUNITYID ) );
                                    appendOutput ( "creator: " + sco.getString ( CObj.CREATOR ) );
                                    appendOutput ( "memid:   " + sco.getString ( CObj.MEMBERID ) );
                                    appendOutput ( "seqnum:  " + sco.getNumber ( CObj.SEQNUM ) );
                                    appendOutput ( "first:   " + sco.getNumber ( CObj.FIRSTNUM ) );
                                    appendOutput ( "wdig:    " + sco.getString ( CObj.FILEDIGEST ) );
                                    appendOutput ( "offset:  " + sco.getNumber ( CObj.FRAGOFFSET ) );
                                }

                                sendCObjNoFlush ( sco );
                            }

                            cl.close();
                        }

                        else
                        {
                            throw new RuntimeException ( "wtf? " + o.getClass().getName() );
                        }

                        updateGui();
                    }

                }

                catch ( Exception e )
                {
                    if ( doLog() )
                    {
                        e.printStackTrace();
                    }

                    stop();
                }

            }

        }

    }

    public static int MAX_PENDING_FILES = 10;
    private int pendingFileRequests = 0;
    private boolean loadFile;
    private int length;

    public int getPendingFileRequests()
    {
        return pendingFileRequests;
    }

    private void readFileData ( InputStream i ) throws IOException
    {
        if ( doLog() )
        {
            appendInput ( "Check for file: " + loadFile );
        }

        if ( loadFile )
        {
            if ( doLog() )
            {
                appendInput ( "Start reading file" );
            }

            byte buf[] = new byte[1024];

            RIPEMD256Digest fdig = new RIPEMD256Digest();
            File tmpf = null;

            if ( tmpDir == null )
            {
                tmpf = File.createTempFile ( "rxfile", ".dat" );
            }

            else
            {
                tmpf = File.createTempFile ( "rxfile", ".dat", tmpDir );
            }

            FileOutputStream fos = new FileOutputStream ( tmpf );
            int rl = 0;

            while ( rl < length )
            {
                int len = i.read ( buf, 0, Math.min ( buf.length, length - rl ) );

                if ( len < 0 )
                {
                    stop();
                    fos.close();
                    throw new IOException ( "End of socket." );
                }

                if ( len > 0 )
                {
                    inBytes += len;
                    fdig.update ( buf, 0, len );
                    fos.write ( buf, 0, len );
                    rl += len;
                    conListener.bytesReceived ( len );
                }

            }

            fos.close();
            byte expdig[] = new byte[fdig.getDigestSize()];
            fdig.doFinal ( expdig, 0 );
            String dstr = Utils.toString ( expdig );

            outproc.decrFileRequests();
            loadFile = false;
            lastMyRequest = System.currentTimeMillis();

            if ( doLog() )
            {
                appendInput ( "OUTPUT THREAD GO! " + pendingFileRequests );
            }

            outproc.go(); //it holds off on local requests until the file is read.

            if ( doLog() )
            {
                appendInput ( "File read " + dstr );
            }

            processFragment ( dstr, tmpf );
            tmpf.delete();
            //now we have it, tell outproc to go again.
        }

    }

    @SuppressWarnings ( "unchecked" )
    private void processFragment ( String dig, File fpart ) throws IOException
    {
        byte buf[] = new byte[1024];
        CObjList flist = index.getFragments ( dig );

        if ( doLog() )
        {
            appendInput ( "matching frags: " + flist.size() );
        }

        for ( int c = 0; c < flist.size(); c++ )
        {
            RandomAccessFile raf = null;
            FileInputStream fis = null;
            Session s = null;
            CObj fg = flist.get ( c );

            String wdig = fg.getString ( CObj.FILEDIGEST );
            String fdig = fg.getString ( CObj.FRAGDIGEST );
            Long fidx = fg.getNumber ( CObj.FRAGOFFSET );
            Long flen = fg.getNumber ( CObj.FRAGSIZE );
            String cplt = fg.getString ( CObj.COMPLETE );

            if ( doLog() )
            {
                appendInput ( " offset: " + fidx + " wdig: " + wdig +
                              " fdig: " + fdig + " flen: " + flen + " state: " + cplt );
            }

            if ( wdig != null && fdig != null && fidx != null &&
                    flen != null && ( !"true".equals ( cplt ) ) )
            {
                try
                {
                    s = session.getSession();
                    Query q = s.createQuery ( "SELECT x FROM RequestFile x WHERE x.wholeDigest = :wdig "
                                              + "AND x.fragmentDigest = :fdig AND x.state != :dstate" );
                    q.setParameter ( "wdig", wdig );
                    q.setParameter ( "fdig", fdig );
                    q.setParameter ( "dstate", RequestFile.COMPLETE );
                    List<RequestFile> lrf = q.list();
                    String lf = null;

                    if ( doLog() )
                    {
                        appendInput ( "matches RequestFiles found: " + lrf.size() );
                    }

                    for ( RequestFile rf : lrf )
                    {
                        //This could change for every fragment, but someone
                        //might find it interesting information.
                        fileDown = rf.getLocalFile();

                        boolean exists = false;
                        lf = rf.getLocalFile();

                        if ( doLog() )
                        {
                            appendInput ( "lf: " + lf );
                        }

                        if ( lf != null )
                        {
                            File f = new File ( lf + RequestFileHandler.AKTIEPART );

                            if ( doLog() )
                            {
                                appendInput ( "Check part file: " + f.getPath() + " exists " + f.exists() );
                            }

                            exists = f.exists();
                        }

                        if ( !exists )
                        {
                            lf = null;
                            s.getTransaction().begin();
                            RequestFile rrf = ( RequestFile ) s.get ( RequestFile.class, rf.getId() );
                            s.delete ( rrf );
                            s.getTransaction().commit();
                        }

                        else
                        {
                            s.getTransaction().begin();
                            rf = ( RequestFile ) s.get ( RequestFile.class, rf.getId() );
                            rf.setFragsComplete ( rf.getFragsComplete() + 1 );
                            s.merge ( rf );

                            if ( doLog() )
                            {
                                appendInput ( "Frags complete: " + rf.getFragsComplete()  );
                            }

                            //Copy the fragment to the whole file.
                            raf = new RandomAccessFile ( rf.getLocalFile() + RequestFileHandler.AKTIEPART, "rw" );
                            fis = new FileInputStream ( fpart );
                            raf.seek ( fidx );
                            int ridx = 0;

                            while ( ridx < flen )
                            {
                                int len = fis.read ( buf, 0, Math.min ( buf.length, ( int ) ( flen - ridx ) ) );

                                if ( len < 0 )
                                {
                                    fis.close();
                                    raf.close();
                                    throw new IOException ( "Oops." );
                                }

                                if ( len > 0 )
                                {
                                    raf.write ( buf, 0, len );
                                    ridx += len;
                                }

                            }

                            raf.close();
                            fis.close();
                            s.getTransaction().commit();
                        }

                        //If we're done, then create a new HasFile for us!
                    }

                    if ( lf != null )
                    {
                        fg.pushPrivate ( CObj.COMPLETE, "true" );
                        fg.pushPrivate ( CObj.LOCALFILE, lf );
                        index.index ( fg );
                        index.forceNewSearcher(); //So we see immediately if we have all frags.
                    }

                    //Refresh the list of RequestFiles in case we deleted any.
                    q = s.createQuery ( "SELECT x FROM RequestFile x WHERE x.wholeDigest = :wdig "
                                        + "AND x.fragmentDigest = :fdig AND x.state != :dstate" );
                    q.setParameter ( "wdig", wdig );
                    q.setParameter ( "fdig", fdig );
                    q.setParameter ( "dstate", RequestFile.COMPLETE );
                    lrf = q.list();

                    //Commit the transaction
                    //Ok now count how many fragments of each is done.
                    if ( doLog() )
                    {
                        appendInput ( "Pending files: " + lrf.size()  );
                    }

                    for ( RequestFile rf : lrf )
                    {
                        s.getTransaction().begin();
                        rf = ( RequestFile ) s.get ( RequestFile.class, rf.getId() );

                        if ( rf != null )
                        {
                            CObjList fdone = index.getFragmentsComplete ( rf.getCommunityId(),
                                             rf.getWholeDigest(), rf.getFragmentDigest() );
                            int numdone = fdone.size();
                            fdone.close();
                            rf.setFragsComplete ( numdone );
                            s.merge ( rf );
                            s.getTransaction().commit();

                            if ( doLog() )
                            {
                                appendInput ( "Fragments complete in index: " + numdone + " <> " + rf.getFragsTotal() );
                            }


                            if ( rf.getFragsComplete() >= rf.getFragsTotal() )
                            {
                                if ( !fileHandler.claimFileComplete ( rf ) )
                                {
                                    if ( doLog() )
                                    {
                                        appendInput ( "Failed to claim complete.." );
                                    }

                                }

                                else
                                {
                                    if ( doLog() )
                                    {
                                        appendInput ( "CLAIM FILE COMPLETE!!!!!!" );
                                    }

                                    //rename the aktiepart file to the real file name
                                    File lff = new File ( rf.getLocalFile() );
                                    File rlp = new File ( rf.getLocalFile() + RequestFileHandler.AKTIEPART );

                                    int lps = 120;

                                    while ( lff.exists() && lps > 0 )
                                    {
                                        lps--;

                                        if ( !lff.delete() )
                                        {
                                            if ( doLog() )
                                            {
                                                log.info ( "Could not delete file: " + lff.getPath() );
                                            }

                                            try
                                            {
                                                Thread.sleep ( 1000L );
                                            }

                                            catch ( InterruptedException e )
                                            {
                                                e.printStackTrace();
                                            }

                                        }

                                    }

                                    lps = 120;

                                    while ( rlp.exists() && lps > 0 )
                                    {
                                        lps--;

                                        if ( !rlp.renameTo ( lff ) )
                                        {
                                            if ( doLog() )
                                            {
                                                log.info ( "Failed to rename: " + rlp.getPath() + " to " + lff.getPath() );
                                            }

                                            try
                                            {
                                                Thread.sleep ( 1000L );
                                            }

                                            catch ( InterruptedException e )
                                            {
                                                e.printStackTrace();
                                            }

                                        }

                                    }

                                    CObj hf = new CObj();
                                    hf.setType ( CObj.HASFILE );
                                    hf.pushString ( CObj.CREATOR, rf.getRequestId() );
                                    hf.pushString ( CObj.COMMUNITYID, rf.getCommunityId() );
                                    hf.pushString ( CObj.NAME, ( new File ( rf.getLocalFile() ) ).getName() );
                                    hf.pushText ( CObj.NAME, hf.getString ( CObj.NAME ) );
                                    hf.pushNumber ( CObj.FRAGSIZE, rf.getFragSize() );
                                    hf.pushNumber ( CObj.FILESIZE, rf.getFileSize() );
                                    hf.pushNumber ( CObj.FRAGNUMBER, rf.getFragsTotal() );
                                    hf.pushString ( CObj.STILLHASFILE, "true" );
                                    hf.pushString ( CObj.FILEDIGEST, rf.getWholeDigest() );
                                    hf.pushString ( CObj.FRAGDIGEST, rf.getFragmentDigest() );
                                    hf.pushPrivate ( CObj.LOCALFILE, rf.getLocalFile() );
                                    hf.pushPrivate ( CObj.UPGRADEFLAG, rf.isUpgrade() ? "true" : "false" );
                                    hf.pushString ( CObj.SHARE_NAME, rf.getShareName() );
                                    //hfc.createHasFile ( hf );
                                    //hfc.updateFileInfo ( hf );
                                    downloadQueue.enqueue ( hf );
                                    update ( hf );

                                }

                            }

                            update ( rf );
                        }

                        else
                        {
                            s.getTransaction().commit();
                        }

                    }

                    s.close();
                }

                catch ( Exception e )
                {
                    e.printStackTrace();

                    if ( s != null )
                    {
                        try
                        {
                            if ( s.getTransaction().isActive() )
                            {
                                s.getTransaction().rollback();
                            }

                            s.close();
                        }

                        catch ( Exception e2 )
                        {
                            e2.printStackTrace();
                        }

                    }

                    if ( raf != null )
                    {
                        try
                        {
                            raf.close();
                        }

                        catch ( Exception e2 )
                        {
                        }

                    }

                    if ( fis != null )
                    {
                        try
                        {
                            fis.close();
                        }

                        catch ( Exception e2 )
                        {
                        }

                    }

                }

            }

            if ( doLog() )
            {
                appendInput ( "DONE PROCESSING FRAGMENT " );
            }

        }

        flist.close();
    }

    public static long LONGESTLIST = 100000000;

    private long lastReadTime;
    private String lastRead = "";
    public String getLastRead()
    {
        return lastRead;
    }

    public long getLastReadTime()
    {
        return lastReadTime;
    }

    private long lastSentTime;
    private String lastSent = "";
    public String getLastSent()
    {
        return lastSent;
    }

    public long getLastSentTime()
    {
        return lastSentTime;
    }

    public long getListCount()
    {
        return listCount;
    }

    @Override
    public void run()
    {
        try
        {
            con.connect();
            outstream = con.getOutputStream();
            outproc = new OutputProcessor();
            Thread t = new Thread ( outproc, "Output Connection Processor Thread" );
            t.start();

            InputStream is = con.getInputStream();
            CleanParser clnpar = new CleanParser ( is );

            while ( !stop )
            {
                if ( doLog() )
                {
                    appendInput ( ".......... wait to read ............" );
                }

                JSONObject jo = clnpar.next();
                inBytes += clnpar.getBytesRead();
                inNonFileBytes += clnpar.getBytesRead();
                conListener.bytesReceived ( clnpar.getBytesRead() );
                CObj r = new CObj();
                r.loadJSON ( jo );
                lastRead = r.getType();
                lastReadTime = System.currentTimeMillis();

                if ( doLog() )
                {
                    appendInput ( r.getType() + "=============" );
                    appendInput ( "dig:     " + r.getDig() );
                    appendInput ( "comid:   " + r.getString ( CObj.COMMUNITYID ) );
                    appendInput ( "creator: " + r.getString ( CObj.CREATOR ) );
                    appendInput ( "memid:   " + r.getString ( CObj.MEMBERID ) );
                    appendInput ( "seqnum:  " + r.getNumber ( CObj.SEQNUM ) );
                    appendInput ( "first:   " + r.getNumber ( CObj.FIRSTNUM ) );
                    appendInput ( "wdig:    " + r.getString ( CObj.FILEDIGEST ) );
                    appendInput ( "offset:  " + r.getNumber ( CObj.FRAGOFFSET ) );
                }

                if ( CObj.CON_LIST.equals ( r.getType() ) )
                {
                    if ( currentList == null )
                    {
                        long lc = r.getNumber ( CObj.COUNT );

                        if ( lc > LONGESTLIST ) { stop(); }

                        listCount = ( int ) lc;

                        if ( doLog() )
                        {
                            appendInput ( "listCount0: " + Integer.toString ( listCount ) );
                        }

                    }

                    else
                    {
                        //This means they sent a new list while one was still
                        //going, this is a bad thing on them.
                        stop();
                    }

                }

                else
                {
                    if ( listCount > 0 )
                    {
                        if ( accumulateTypes.contains ( r.getType() ) )
                        {
                            if ( currentList == null )
                            {
                                currentList = new LinkedList<CObj>();
                            }

                            currentList.add ( r );
                            listCount--;

                            if ( doLog() )
                            {
                                appendInput ( "listCount1: " + Integer.toString ( listCount ) );
                            }

                        }

                        else
                        {
                            currentList = null;
                            listCount = 0;
                        }

                    }

                    //If we're populating a list, then don't process until
                    //we have the whole list, at which time listCount will be zero
                    //if we're not collecting a list listCount is always zero
                    if ( listCount == 0 )
                    {
                        if ( doLog() )
                        {
                            appendInput ( "listCount ZERO currentlist: " + currentList );
                        }

                        if ( currentList == null )
                        {
                            //Not a list, just process it.
                            try
                            {
                                if ( doLog() )
                                {
                                    appendInput ( "BEGIN WaitForProcess preprocQueue: " + r );
                                }

                                WaitForProcess.waitForProcess ( this, r, preprocQueue );

                                //preprocProcessor.processCObj ( r );
                                if ( doLog() )
                                {
                                    appendInput ( "END WaitForProcess preprocQueue: " + r );
                                }

                            }

                            catch ( Exception e )
                            {
                                //Make sure we can debug processing bugs
                                e.printStackTrace();
                                stop();
                            }

                        }

                        else
                        {
                            try
                            {
                                outproc.decrFileRequests();

                                if ( doLog() )
                                {
                                    appendInput ( "BEGIN WaitForProcess preprocQueue2: " + r );
                                }

                                WaitForProcess.waitForProcess ( this, currentList, preprocQueue );
                                //preprocProcessor.processObj ( currentList );

                                if ( doLog() )
                                {
                                    appendInput ( "END WaitForProcess preprocQueue2: " + r );
                                }

                            }

                            catch ( Exception e )
                            {
                                //Make sure we can debug processing bugs
                                e.printStackTrace();
                                stop();
                            }

                            currentList = null;
                        }

                    }

                    readFileData ( is );
                }

                //updateGui();
                outproc.go();
            }

        }

        catch ( Exception e )
        {
            if ( doLog() )
            {
                e.printStackTrace();
            }

        }

        stop();
    }

    long nextupdate = 0;
    private void updateGui()
    {
        long t = System.currentTimeMillis();

        if ( t >= nextupdate )
        {
            nextupdate = t + GUIUPDATEPERIOD;
            conListener.update ( This );
        }

    }

    public long getInNonFileBytes()
    {
        return inNonFileBytes;
    }

    public long getLastMyRequest()
    {
        return lastMyRequest;
    }

    @Override
    public void update ( Object o )
    {
        guicallback.update ( o );
        lastMyRequest = System.currentTimeMillis();

        if ( o instanceof CObj )
        {
            CObj co = ( CObj ) o;

            if ( CObj.COMMUNITY.equals ( co.getType() ) )
            {
                sendFirstMemSubs = true;
                updateSubsAndFiles();
            }

            if ( CObj.SUBSCRIPTION.equals ( co.getType() ) )
            {
                sendFirstMemSubs = true;
                updateSubsAndFiles();
            }

            if ( CObj.MEMBERSHIP.equals ( co.getType() ) )
            {
                sendFirstMemSubs = true;
                updateSubsAndFiles();
            }

        }

    }

    private PrintWriter outtrace;
    private PrintWriter intrace;

    private boolean logit = false;
    public void toggleLogging()
    {
        if ( logit )
        {
            appendInput ( "STOP LOGGING!" );
            appendOutput ( "STOP LOGGING!" );
        }

        logit = !logit;

        if ( !logit )
        {
            PrintWriter it = intrace;
            intrace = null;

            if ( it != null )
            {
                try
                {
                    it.close();
                }

                catch ( Exception e )
                {
                    e.printStackTrace();
                }

            }

            PrintWriter ot = outtrace;
            outtrace = null;

            if ( ot != null )
            {
                try
                {
                    ot.close();
                }

                catch ( Exception e )
                {
                    e.printStackTrace();
                }

            }

        }

        else
        {
            appendInput ( "START LOGGING!" );
            appendOutput ( "START LOGGING!" );
        }

    }

    private boolean doLog()
    {
        return logit || Level.INFO.equals ( log.getLevel() );
    }

    public boolean getLogging()
    {
        return logit;
    }

    private void appendOutput ( String msg )
    {
        if ( doLog() )
        {
            if ( endDestination != null )
            {
                if ( outtrace == null )
                {
                    String myid = dest.getIdentity().getId().substring ( 0, 6 );
                    String oid = endDestination.getId().substring ( 0, 6 );
                    String n = "out_" + myid + "_to_" + oid + ".trace";
                    n = n.replaceAll ( "\\\\", "_" );
                    n = n.replaceAll ( "/", "_" );
                    n = n.replaceAll ( ":", "_" );
                    n = n.replaceAll ( ";", "_" );
                    File f = new File ( n );
                    int idx = 0;

                    while ( f.exists() )
                    {
                        f = new File ( n + idx );
                        idx++;
                    }

                    try
                    {
                        outtrace = new PrintWriter ( new BufferedWriter ( new FileWriter ( f.getPath(), true ) ) );
                    }

                    catch ( Exception e )
                    {
                        e.printStackTrace();
                    }

                }

                if ( outtrace != null )
                {
                    appendLog ( outtrace, msg );
                }

            }

        }

    }

    private void appendInput ( String msg )
    {
        if ( doLog() )
        {
            if ( endDestination != null )
            {
                if ( intrace == null )
                {
                    String myid = dest.getIdentity().getId().substring ( 0, 6 );
                    String oid = endDestination.getId().substring ( 0, 6 );
                    String n = "in_" + myid + "_to_" + oid + ".trace";
                    n = n.replaceAll ( "\\\\", "_" );
                    n = n.replaceAll ( "/", "_" );
                    n = n.replaceAll ( ":", "_" );
                    n = n.replaceAll ( ";", "_" );
                    File f = new File ( n );
                    int idx = 0;

                    while ( f.exists() )
                    {
                        f = new File ( n + idx );
                        idx++;
                    }

                    try
                    {
                        intrace = new PrintWriter ( new BufferedWriter ( new FileWriter ( f.getPath(), true ) ) );
                    }

                    catch ( Exception e )
                    {
                        e.printStackTrace();
                    }

                }

                if ( intrace != null )
                {
                    appendLog ( intrace, msg );
                }

            }

        }

    }

    private void appendLog ( PrintWriter pw, String s )
    {
        if ( pw != null )
        {
            try
            {
                s = System.currentTimeMillis() + ":: " + s;
                pw.println ( s );
                pw.flush();
            }

            catch ( Exception e )
            {
                e.printStackTrace();
            }

        }

    }

    public String getFileUp()
    {
        return fileUp;
    }

    public void setFileUp ( String fileUp )
    {
        this.fileUp = fileUp;
    }

    public String getFileDown()
    {
        return fileDown;
    }

    public void setFileDown ( String fileDown )
    {
        this.fileDown = fileDown;
    }

    public Set<String> getMemberships()
    {
        return memberships;
    }

    public Set<String> getSubs()
    {
        return subs;
    }

    public boolean isUpdatemembers()
    {
        return updatemembers;
    }

    public boolean isUpdatesubs()
    {
        return updatesubs;
    }

}
