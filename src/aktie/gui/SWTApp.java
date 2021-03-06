package aktie.gui;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import aktie.UpdateCallback;
import aktie.Wrapper;
import aktie.IdentityBackupRestore;
import aktie.IdentityCache;
import aktie.Node;
import aktie.StandardI2PNode;
import aktie.data.CObj;
import aktie.data.DeveloperIdentity;
import aktie.data.DirectoryShare;
import aktie.data.HH2Session;
import aktie.data.RequestFile;
import aktie.gui.launchers.LauncherDialog;
import aktie.gui.pm.PMTab;
import aktie.gui.pm.PrivateMessageDialog;
import aktie.gui.subtree.SubTreeDragListener;
import aktie.gui.subtree.SubTreeDropListener;
import aktie.gui.subtree.SubTreeEntity;
import aktie.gui.subtree.SubTreeEntityDB;
import aktie.gui.subtree.SubTreeLabelProvider;
import aktie.gui.subtree.SubTreeListener;
import aktie.gui.subtree.SubTreeModel;
import aktie.gui.subtree.SubTreeSorter;
import aktie.index.CObjList;
import aktie.net.ConnectionElement;
import aktie.net.ConnectionListener;
import aktie.net.ConnectionThread;
import aktie.upgrade.UpgradeControllerCallback;
import aktie.user.ShareListener;

import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.custom.PaintObjectEvent;
import org.eclipse.swt.custom.PaintObjectListener;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.ImageRegistry;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;

import swing2swt.layout.BorderLayout;

import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.swt.events.VerifyListener;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Label;
import org.json.JSONObject;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.jface.viewers.ComboViewer;

public class SWTApp implements UpdateInterface, UpgradeControllerCallback
{

    Logger log = Logger.getLogger ( "aktie" );

    private ConnectionCallback concallback = new ConnectionCallback();

    interface ConnectionColumnGetText
    {
        public String getText ( Object element );
    }

    public static long UPDATE_INTERVAL = 1000;

    class ConnectionCallback implements ConnectionListener
    {
        public Set<ConnectionThread> connections = new HashSet<ConnectionThread>();

        private long totalInBytes = 0L;
        private long totalOutBytes = 0L;
        // Use individual fine grained locks
        // synchronized methods would block each other
        // see https://docs.oracle.com/javase/tutorial/essential/concurrency/locksync.html
        private Object inByteLock = new Object();
        private Object outByteLock = new Object();

        @Override
        public void bytesReceived ( long bytes )
        {
            synchronized ( inByteLock )
            {
                totalInBytes += bytes;
            }

        }

        @Override
        public void bytesSent ( long bytes )
        {
            synchronized ( outByteLock )
            {
                totalOutBytes += bytes;
            }

        }

        public long getTotalInBytes()
        {
            return totalInBytes;
        }

        public long getTotalOutBytes()
        {
            return totalOutBytes;
        }

        @Override
        public void update ( ConnectionThread ct )
        {
            if ( !ct.isStopped() )
            {
                synchronized ( connections )
                {
                    connections.add ( ct );
                }

                //updateDisplay ( false );
            }

            else
            {
                closed ( ct );
            }

        }

        @Override
        public void closed ( ConnectionThread ct )
        {
            synchronized ( connections )
            {
                connections.remove ( ct );
            }

            //updateDisplay ( true );
        }

        public ConnectionElement[] getElements()
        {
            ConnectionElement r[] = null;

            synchronized ( connections )
            {
                r = new ConnectionElement[connections.size()];
                Iterator<ConnectionThread> i = connections.iterator();
                int idx = 0;

                while ( i.hasNext() )
                {
                    r[idx] = new ConnectionElement ( i.next() );
                    idx++;
                }

            }

            return r;
        }

    }

    private NetCallback netcallback = new NetCallback();

    private void updateBanner ( CObj co )
    {
        String creator = co.getString ( CObj.CREATOR );

        DeveloperIdentity di = null;

        if ( creator != null )
        {
            di = node.getDeveloper ( creator );
        }

        if ( di != null )
        {

            //Update subject line
            final String subj = co.getString ( CObj.SUBJECT );

            if ( subj != null )
            {
                Wrapper.saveLastDevMessage ( subj );

                Display.getDefault().asyncExec ( new Runnable()
                {
                    @Override
                    public void run()
                    {
                        if ( bannerText != null && !bannerText.isDisposed() )
                        {
                            bannerText.setText ( subj );
                        }

                    }

                } );

            }

        }

    }

    class NetCallback implements UpdateCallback
    {
        @Override
        public void update ( Object o )
        {
            if ( o instanceof RequestFile )
            {
                if ( downloadsTable != null )
                {
                    Display.getDefault().asyncExec ( new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            downloadsTable.getTableViewer().setInput (
                                SWTApp.this.node.getNode().getFileHandler() );
                        }

                    } );

                }

            }

            if ( o instanceof CObj )
            {
                if ( o != null )
                {
                    final CObj co = ( ( CObj ) o ).clone();

                    if ( co.getString ( CObj.ERROR ) != null )
                    {
                        boolean clear = true;
                        String dclear = co.getPrivate ( CObj.PRV_CLEAR_ERR );

                        if ( "false".equals ( dclear ) )
                        {
                            clear = false;
                        }

                        setErrorMessage ( co.getString ( CObj.ERROR ), clear );
                    }

                    else
                    {
                        String type = co.getType();
                        String comid = co.getString ( CObj.COMMUNITYID );

                        if ( CObj.POST.equals ( type ) )
                        {

                            updateBanner ( co );

                            Display.getDefault().asyncExec ( new Runnable()
                            {
                                @Override
                                public void run()
                                {
                                    addData ( co );
                                }

                            } );

                            if ( selectedCommunity != null && comid != null && comid.equals ( selectedCommunity.getDig() ) )
                            {

                                Display.getDefault().asyncExec ( new Runnable()
                                {
                                    @Override
                                    public void run()
                                    {
                                        postSearch();
                                    }

                                } );

                            }

                        }

                        if ( CObj.MEMBERSHIP.equals ( type ) || CObj.COMMUNITY.equals ( type ) )
                        {
                            if ( "true".equals ( co.getPrivate ( CObj.MINE ) ) )
                            {
                                Display.getDefault().asyncExec ( new Runnable()
                                {
                                    @Override
                                    public void run()
                                    {
                                        updateMembership();
                                    }

                                } );

                            }

                        }

                        if ( CObj.HASFILE.equals ( type ) )
                        {
                            if ( selectedCommunity != null && comid != null && comid.equals ( selectedCommunity.getDig() ) )
                            {
                                Display.getDefault().asyncExec ( new Runnable()
                                {
                                    @Override
                                    public void run()
                                    {
                                        filesSearch();
                                        updateShareCount();
                                    }

                                } );

                            }

                        }

                        if ( CObj.PRIVIDENTIFIER.equals ( co.getType() ) ||
                                CObj.PRIVMESSAGE.equals ( co.getType() ) )
                        {
                            Display.getDefault().asyncExec ( new Runnable()
                            {
                                @Override
                                public void run()
                                {
                                    pmTab.update ( co );
                                }

                            } );

                        }

                    }

                }

            }

        }

    }

    public UsrCallback getUserCallback()
    {
        return usrcallback;
    }

    private UsrCallback usrcallback = new UsrCallback();

    class UsrCallback implements UpdateCallback
    {
        @Override
        public void update ( Object o )
        {
            if ( o instanceof RequestFile )
            {
                if ( downloadsTable != null )
                {
                    Display.getDefault().asyncExec ( new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            downloadsTable.getTableViewer().setInput (
                                SWTApp.this.node.getNode().getFileHandler() );
                        }

                    } );

                }

            }

            if ( o instanceof CObj )
            {
                final CObj co = ( CObj ) o;

                if ( co.getString ( CObj.ERROR ) != null )
                {
                    boolean clear = true;
                    String dclear = co.getPrivate ( CObj.PRV_CLEAR_ERR );

                    if ( "false".equals ( dclear ) )
                    {
                        clear = false;
                    }

                    setErrorMessage ( co.getString ( CObj.ERROR ), clear );
                }

                else
                {
                    setErrorMessage ( "", false );
                    String comid = co.getString ( CObj.COMMUNITYID );

                    if ( CObj.FILE.equals ( co.getType() ) )
                    {
                        Display.getDefault().asyncExec ( new Runnable()
                        {
                            @Override
                            public void run()
                            {
                                filesSearch();
                            }

                        } );

                    }

                    if ( CObj.IDENTITY.equals ( co.getType() ) )
                    {
                        final String name = co.getDisplayName();

                        if ( identTreeViewer != null && name != null )
                        {
                            Display.getDefault().asyncExec ( new Runnable()
                            {
                                @Override
                                public void run()
                                {
                                    addData ( co );
                                }

                            } );

                        }

                    }

                    if ( CObj.COMMUNITY.equals ( co.getType() ) )
                    {
                        CObj sub = new CObj();
                        sub.setType ( CObj.SUBSCRIPTION );
                        sub.pushString ( CObj.CREATOR, co.getString ( CObj.CREATOR ) );
                        sub.pushString ( CObj.COMMUNITYID, co.getDig() );
                        sub.pushString ( CObj.SUBSCRIBED, "true" );
                        node.getNode().enqueue ( sub );
                    }

                    if ( CObj.SUBSCRIPTION.equals ( co.getType() ) )
                    {
                        final String creatorid = co.getString ( CObj.CREATOR );

                        if ( creatorid != null && comid != null )
                        {
                            Display.getDefault().asyncExec ( new Runnable()
                            {
                                @Override
                                public void run()
                                {
                                    addData ( co );
                                }

                            } );

                        }

                    }

                    if ( CObj.POST.equals ( co.getType() ) )
                    {

                        updateBanner ( co );

                        Display.getDefault().asyncExec ( new Runnable()
                        {
                            @Override
                            public void run()
                            {
                                addData ( co );
                            }

                        } );

                        if ( selectedCommunity != null && comid != null && comid.equals ( selectedCommunity.getDig() ) )
                        {
                            Display.getDefault().asyncExec ( new Runnable()
                            {
                                @Override
                                public void run()
                                {
                                    postSearch();
                                }

                            } );

                        }

                    }

                    if ( CObj.MEMBERSHIP.equals ( co.getType() ) ||
                            CObj.COMMUNITY.equals ( co.getType() ) )
                    {
                        if ( "true".equals ( co.getPrivate ( CObj.MINE ) ) )
                        {
                            Display.getDefault().asyncExec ( new Runnable()
                            {
                                @Override
                                public void run()
                                {
                                    updateMembership();
                                }

                            } );

                        }

                    }

                    if ( CObj.HASFILE.equals ( co.getType() ) )
                    {

                        checkPendingPosts ( co );

                        if ( selectedCommunity != null && comid != null && comid.equals ( selectedCommunity.getDig() ) )
                        {
                            Display.getDefault().asyncExec ( new Runnable()
                            {
                                @Override
                                public void run()
                                {
                                    filesSearch();
                                    updateShareCount();
                                }

                            } );

                        }

                    }

                    if ( CObj.PRIVIDENTIFIER.equals ( co.getType() ) ||
                            CObj.PRIVMESSAGE.equals ( co.getType() ) )
                    {
                        Display.getDefault().asyncExec ( new Runnable()
                        {
                            @Override
                            public void run()
                            {
                                pmTab.update ( co );
                            }

                        } );

                    }

                }

            }

        }

    }

    class SaveSeeds implements SelectionListener
    {
        @Override
        public void widgetSelected ( SelectionEvent event )
        {
            FileDialog fd = new FileDialog ( shell, SWT.SAVE );
            fd.setText ( "Save" );
            //fd.setFilterPath();
            String[] filterExt = { "*.*" };

            fd.setFilterExtensions ( filterExt );
            String selected = fd.open();

            if ( node != null && selected != null )
            {
                node.saveSeeds ( new File ( selected ) );
            }

        }

        @Override
        public void widgetDefaultSelected ( SelectionEvent event )
        {
        }

    }

    class LoadSeeds implements SelectionListener
    {
        @Override
        public void widgetSelected ( SelectionEvent event )
        {
            FileDialog fd = new FileDialog ( shell, SWT.OPEN );
            fd.setText ( "Open" );
            //fd.setFilterPath();
            String[] filterExt = { "*.*" };

            fd.setFilterExtensions ( filterExt );
            String selected = fd.open();

            if ( selected != null && node != null )
            {
                node.loadSeed ( new File ( selected ) );
            }

        }

        @Override
        public void widgetDefaultSelected ( SelectionEvent event )
        {
        }

    }

    class AddFile implements SelectionListener
    {
        @Override
        public void widgetSelected ( SelectionEvent evt )
        {
            if ( selectedCommunity != null && selectedIdentity != null )
            {
                FileDialog fd = new FileDialog ( shell, SWT.OPEN | SWT.MULTI );
                fd.setText ( "Add File" );
                //fd.setFilterPath();
                String[] filterExt =
                {
                    "*",
                    "*.txt",
                    "*.pdf",
                    "*.exe",
                    "*.jpg",
                    "*.jpeg",
                    "*.png",
                    "*.gif",
                    "*.bmp",
                    "*.mov",
                    "*.mpg",
                    "*.mpeg",
                    "*.avi",
                    "*.flv",
                    "*.wmv",
                    "*.webv",
                    "*.rm"
                };

                fd.setFilterExtensions ( filterExt );
                fd.open();
                String selary[] = fd.getFileNames();
                String selpath = fd.getFilterPath();

                for ( int c = 0; c < selary.length; c++ )
                {
                    File f = new File ( selpath + File.separator + selary[c] );

                    if ( f.exists() )
                    {
                        if ( f.isFile() )
                        {

                            DeveloperIdentity di = node.getDeveloper ( selectedIdentity.getId() );

                            CObj nf = new CObj();
                            nf.setType ( CObj.HASFILE );
                            nf.pushString ( CObj.COMMUNITYID, selectedCommunity.getDig() );
                            nf.pushString ( CObj.CREATOR, selectedIdentity.getId() );
                            nf.pushPrivate ( CObj.LOCALFILE, f.getPath() );

                            if ( di != null )
                            {
                                if ( MessageDialog.openConfirm ( shell, "Update", "Are you sure you want this to be an update file?" ) )
                                {
                                    nf.pushString ( CObj.UPGRADEFLAG, "true" );
                                    //Set private value too so that we say we have it for ourself.
                                    nf.pushPrivate ( CObj.UPGRADEFLAG, "true" );
                                    node.getNode().enqueue ( nf );
                                }

                            }

                            else
                            {
                                node.getNode().enqueue ( nf );
                            }

                        }

                    }

                }

            }

            else
            {
                MessageDialog.openWarning ( shell, "Select a community.", "Sorry, you have to select the community you wish to add a file to." );
            }

        }

        @Override
        public void widgetDefaultSelected ( SelectionEvent e )
        {
        }

    }

    private void generateSpamEx ( CObj devid, boolean save )
    {
        if ( devid != null )
        {
            CObj u = new CObj();
            u.setType ( CObj.SPAMEXCEPTION );
            u.pushString ( CObj.CREATOR, devid.getId() );

            if ( save )
            {
                u.pushPrivate ( CObj.STATUS, "save" );
            }

            node.getNode().enqueue ( u );
        }

    }

    private SWTSplash splash;
    private Label lblVersion;
    private Label lblSpeed;
    private boolean doUpgrade = true;
    private Shell shell;
    private Text postsSearchText;
    private Tree identTree;
    private TreeViewer identTreeViewer;
    private NewCommunityDialog newCommunityDialog;
    private ShowHasFileDialog hasFileDialog;
    private NewIdentityDialog newIdentityDialog;
    private SubscriptionDialog subscriptionDialog;
    private NewMemberDialog newMemberDialog;
    private NewPostDialog newPostDialog;
    private DownloadPriorityDialog downloadPriorityDialog;
    private ShowPrivateCommunityDialog privComDialog;
    private ShowMembersDialog membersDialog;
    private NewDirectoryShareDialog shareDialog;
    private DownloadToShareDialog downloadToShareDialog;
    private I2PSettingsDialog i2pDialog;
    private AddFolderDialog addFolderDialog;
    private AdvancedSearchDialog advancedDialog;
    private SetUserRankDialog userRankDialog;
    private ZeroIdentityDialog zeroDialog;
    private SpamRankDialog spamDialog;
    private PaymentThreadsDialog threadDialog;
    private PrivateMessageDialog prvMsgDialog;
    private LauncherDialog launcherDialog;
    private ConnectionDialog connectionDialog;
    private SelectIdentityDialog devIdentDialog;

    private PMTab pmTab;

    //private IdentitySubTreeModel identSubTreeModel;
    private SubTreeModel identModel;

    private StandardI2PNode node;
    private String nodeDir;

    private CObj selectedIdentity;
    private CObj selectedCommunity;
    private Label lblIdentCommunity;
    private PostsTable postsTable;
    private StyledText postText;
    private ConnectionTable connectionTable;
    private Text filesSearchText;
    private FilesTable filesTable;
    private DownloadsTable downloadsTable;
    private String exportCommunitiesFile;
    private Map<String, List<CObj>> pendingPosts = new HashMap<String, List<CObj>>();
    private CObj advQuery;

    //Should be a HASFILE
    private void checkPendingPosts ( CObj c )
    {
        String lfname = c.getPrivate ( CObj.LOCALFILE );

        if ( lfname != null )
        {
            synchronized ( pendingPosts )
            {
                List<CObj> pplst = pendingPosts.get ( lfname );

                if ( pplst != null )
                {
                    Iterator<CObj> i = pplst.iterator();

                    while ( i.hasNext() )
                    {
                        CObj pst = i.next();
                        String fname = pst.getPrivate ( CObj.LOCALFILE );
                        String pvname = pst.getPrivate ( CObj.PRV_LOCALFILE );

                        //Primary file attachment
                        if ( lfname.equals ( fname ) )
                        {
                            pst.pushString ( CObj.NAME,       c.getString ( CObj.NAME ) );
                            pst.pushNumber ( CObj.FILESIZE,   c.getNumber ( CObj.FILESIZE ) );
                            pst.pushString ( CObj.FRAGDIGEST, c.getString ( CObj.FRAGDIGEST ) );
                            pst.pushNumber ( CObj.FRAGSIZE,   c.getNumber ( CObj.FRAGSIZE ) );
                            pst.pushNumber ( CObj.FRAGNUMBER, c.getNumber ( CObj.FRAGNUMBER ) );
                            pst.pushString ( CObj.FILEDIGEST, c.getString ( CObj.FILEDIGEST ) );
                        }

                        if ( lfname.equals ( pvname ) )
                        {
                            pst.pushString ( CObj.PRV_NAME,       c.getString ( CObj.NAME ) );
                            pst.pushNumber ( CObj.PRV_FILESIZE,   c.getNumber ( CObj.FILESIZE ) );
                            pst.pushString ( CObj.PRV_FRAGDIGEST, c.getString ( CObj.FRAGDIGEST ) );
                            pst.pushNumber ( CObj.PRV_FRAGSIZE,   c.getNumber ( CObj.FRAGSIZE ) );
                            pst.pushNumber ( CObj.PRV_FRAGNUMBER, c.getNumber ( CObj.FRAGNUMBER ) );
                            pst.pushString ( CObj.PRV_FILEDIGEST, c.getString ( CObj.FILEDIGEST ) );
                        }

                        if ( ( fname == null ||
                                ( fname != null && pst.getString ( CObj.FILEDIGEST ) != null ) ) &&
                                ( pvname == null ||
                                  ( pvname != null && pst.getString ( CObj.PRV_FILEDIGEST ) != null ) ) )
                        {
                            i.remove();
                            node.getNode().enqueue ( pst );
                        }

                    }

                    if ( pplst.size() == 0 )
                    {
                        pendingPosts.remove ( lfname );
                    }

                }

            }

        }

    }

    public void addPendingPost ( CObj c )
    {
        String fname = c.getPrivate ( CObj.LOCALFILE );
        String pvname = c.getPrivate ( CObj.PRV_LOCALFILE );

        if ( fname != null )
        {
            synchronized ( pendingPosts )
            {
                List<CObj> pl = pendingPosts.get ( fname );

                if ( pl == null )
                {
                    pl = new LinkedList<CObj>();
                    pendingPosts.put ( fname, pl );
                }

                pl.add ( c );
            }

        }

        if ( pvname != null )
        {
            synchronized ( pendingPosts )
            {
                List<CObj> pl = pendingPosts.get ( pvname );

                if ( pl == null )
                {
                    pl = new LinkedList<CObj>();
                    pendingPosts.put ( pvname, pl );
                }

                pl.add ( c );
            }

        }

    }

    public CObj getSelectedCommunity()
    {
        return selectedCommunity;
    }

    public CObj getSelectedIdentity()
    {
        return selectedIdentity;
    }

    public HH2Session getSession()
    {
        return session;
    }

    private void setBlogMode ( CObj id, CObj comid )
    {
        boolean blg = false;

        if ( "true".equals ( comid.getString ( CObj.BLOGMODE ) ) )
        {
            String ids = id.getId();

            if ( ids != null && ids.equals ( comid.getString ( CObj.CREATOR ) ) )
            {
                blg = false;
            }

            else
            {
                blg = true;
            }

        }

        btnDelete.setEnabled ( !blg );
        btnShare.setEnabled ( !blg );
        textShareName.setEditable ( !blg );
        textSharePath.setEditable ( !blg );
        textNumberSubDirs.setEditable ( !blg );
        textNumberFiles.setEditable ( !blg );
        shareCombo.setEnabled ( !blg );
        comboShareName.setEnabled ( !blg );
        btnDefaultDownloadLocation.setEnabled ( !blg );
        btnDoNotGenerate.setEnabled ( !blg );
        txtAShareIs.setEditable ( !blg );
        btnPost.setEnabled ( !blg );
        mntmReply.setEnabled ( !blg );
        mntmCreatePost.setEnabled ( !blg );
        btnAddFiles.setEnabled ( !blg );
    }

    public void setSelected ( CObj id, CObj comid )
    {
        selectedIdentity = id;
        selectedCommunity = comid;
        lblIdentCommunity.setText ( "Identity: " + selectedIdentity.getDisplayName() +
                                    "  Community: " + selectedCommunity.getPrivateDisplayName() );

        //TODO: Do this again identSubTreeModel.clearNew ( comid );
        identModel.clearBlue ( comid );
        identTreeViewer.refresh ( true );

        setShares ( comid.getDig(), id.getId() );

        postsSearchText.setText ( "" );
        filesSearchText.setText ( "" );
        advQuery = null;
        postSearch ( );
        filesSearch();
        postText.setText ( "" );
        animator.update ( null, 0, 0, 10, 10 );

        setBlogMode ( id, comid );
    }

    public void setVerbose()
    {
        System.out.println ( "SETTING VERBOSE-!-" );
        log.setLevel ( Level.INFO );
    }

    public void setSevere()
    {
        log.setLevel ( Level.SEVERE );
    }

    public IdentityCache getIdCache()
    {
        return node.getIdCache();
    }

    public  Node getNode()
    {
        return node.getNode();
    }

    public StandardI2PNode getStandardI2PNode()
    {
        return node;
    }

    /**
        Launch the application.
        @param args
    */
    public static void main ( String[] args )
    {

        boolean verbose = false;

        try
        {

            SWTApp window = new SWTApp();

            if ( args.length > 0 )
            {
                window.nodeDir = args[0];

                if ( args.length > 1 )
                {
                    if ( "-v".equals ( args[1] ) )
                    {
                        verbose = true;
                    }

                    else
                    {
                        window.exportCommunitiesFile = args[1];
                    }

                }

                if ( args.length > 2 )
                {
                    for ( int ct = 2; ct < args.length && !verbose; ct++ )
                    {
                        verbose = "-v".equals ( args[ct] );
                    }

                }

            }

            else
            {
                window.nodeDir = "aktie_node";
            }

            //Auto save backup file.
            IdentityBackupRestore ibak = new IdentityBackupRestore();
            ibak.init ( window.nodeDir, window.nodeDir + File.separator + "i2p" );
            File autobak = new File ( window.nodeDir + File.separator + "auto_backup.dat" );
            File autobak2 = new File ( window.nodeDir + File.separator + "auto_backup.dat.bak" );

            if ( autobak.exists() )
            {
                autobak2.delete();
                autobak.renameTo ( autobak2 );
                autobak.delete();
            }

            ibak.saveIdentity ( autobak );
            ibak.close();

            if ( verbose )
            {
                window.setVerbose();
            }

            else
            {
                window.setSevere();
            }

            window.open();
        }

        catch ( Exception e )
        {
            e.printStackTrace();
        }

        System.exit ( 0 );

    }

    private void startNodeThread ( final Properties p )
    {
        final SWTApp app = this;
        Thread t = new Thread ( new Runnable()
        {
            @Override
            public void run()
            {
                node = new StandardI2PNode();
                node.bootNode ( nodeDir, p, usrcallback,
                                netcallback, concallback, app );

                node.getNode().getShareManager().setShareListener ( new ShareListener()
                {

                    @Override
                    public void shareManagerRunning ( final boolean running )
                    {
                        Display.getDefault().asyncExec ( new Runnable()
                        {
                            @Override
                            public void run()
                            {
                                if ( running )
                                {
                                    lblNotRunning.setText ( "Share Manager is running" );
                                }

                                else
                                {
                                    lblNotRunning.setText ( "Share Manager" );
                                }

                            }

                        } );

                    }

                } );

                final boolean en = Wrapper.getEnabledShareManager();
                node.getNode().getShareManager().setEnabled ( en );

                Display.getDefault().asyncExec ( new Runnable()
                {
                    @Override
                    public void run()
                    {
                        startedSuccessfully();

                        if ( node.getNode().getShareManager().isRunning() )
                        {
                            lblNotRunning.setText ( "Share Manager is running" );
                        }

                        else
                        {
                            lblNotRunning.setText ( "Share Manager" );
                        }

                        btnEnableShareManager.setSelection ( en );

                    }

                } );

            }

        }, "Node start thread" );

        t.start();
    }

    private Properties getI2PReady()
    {
        File i2pp = new File ( nodeDir + File.separator + "i2p.props" );
        i2pDialog = new I2PSettingsDialog ( shell, this, i2pp );
        i2pDialog.create();

        return i2pDialog.getI2PProps();
    }

    public SubTreeModel getIdentModel()
    {
        return identModel;
    }

    public void addFolder ( SubTreeEntity e, String name )
    {
        identModel.addFolder ( e, name );
        identTreeViewer.setInput ( "folder added" );
        identModel.setCollaspseState ( identTreeViewer );
    }

    private void startedSuccessfully()
    {

        //identSubTreeModel = new IdentitySubTreeModel ( this );
        //identTreeViewer.setContentProvider ( new IdentitySubTreeProvider() );
        // Post tree must be id 0, the default for the new treeid value is 0 so
        // existing entitys for the posts tree will get id 0
        session = new HH2Session();
        session.init ( nodeDir + File.separator + "h2gui" );

        identModel = new SubTreeModel ( node.getNode().getIndex(),
                                        new SubTreeEntityDB ( session ),
                                        SubTreeModel.POST_TREE, 0 );
        identModel.init();
        identTreeViewer.setContentProvider ( identModel );
        SubTreeListener stl = new SubTreeListener ( identModel );
        identTreeViewer.addTreeListener ( stl );
        //identTreeViewer.setLabelProvider();
        identTreeViewer.setSorter ( new SubTreeSorter() );
        int operations = DND.DROP_COPY | DND.DROP_MOVE;
        Transfer[] transferTypes = new Transfer[] {TextTransfer.getInstance() };

        identTreeViewer.addDragSupport ( operations, transferTypes ,
                                         new SubTreeDragListener ( identTreeViewer ) );
        identTreeViewer.addDropSupport ( operations, transferTypes,
                                         new SubTreeDropListener ( identTreeViewer, identModel ) );

        TreeViewerColumn tvc1 = new TreeViewerColumn ( identTreeViewer, SWT.NONE );
        tvc1.getColumn().setText ( "Name" ); //$NON-NLS-1$
        tvc1.getColumn().setWidth ( 200 );
        tvc1.setLabelProvider ( new DelegatingStyledCellLabelProvider (
                                    new SubTreeLabelProvider ( identModel ) ) );
        //tvc1.setLabelProvider ( new DelegatingStyledCellLabelProvider (
        //                            new IdentitySubTreeLabelProvider() ) );

        identTreeViewer.refresh();

        pmTab.init();

        node.nodeStarted();

        pmTab.updateMessages();

        createDialogs();
        exportCommunities();

        //      saveVersionFile();
        startNetUpdateStatusTimer();
        //
        //      CObj u = new CObj();
        //      u.setType ( CObj.USR_SPAMEX_UPDATE );
        //      getNode().enqueue ( u );

    }


    private boolean pendingClear = false;

    private void setErrorMessage ( final String msg, final boolean clear )
    {
        Display.getDefault().asyncExec ( new Runnable()
        {
            @Override
            public void run()
            {
                lblError.setText ( msg );
                composite_header.layout();

                if ( !pendingClear && clear )
                {
                    pendingClear = true;
                    Timer t = new Timer ( "Error message clear", true );
                    t.schedule ( new TimerTask()
                    {
                        @Override
                        public void run()
                        {
                            pendingClear = false;
                            Display.getDefault().asyncExec ( new Runnable()
                            {
                                @Override
                                public void run()
                                {
                                    lblError.setText ( "" );
                                    composite_header.layout();
                                }

                            } );

                        }

                    }, 5L * 60L * 1000L );

                }

            }

        } );

    }

    private void setVersionNetStatus()
    {
        String netstat = "";

        if ( node.getNode() != null )
        {
            if ( node.getNode().getNet() != null )
            {
                netstat = node.getNode().getNet().getStatus();
            }

        }

        lblVersion.setText ( Wrapper.VERSION + " (" + netstat + ")" );
    }

    private void startNetUpdateStatusTimer()
    {
        Timer t = new Timer ( "I2P Update timer", true );
        t.schedule ( new TimerTask()
        {
            @Override
            public void run()
            {

                Display.getDefault().asyncExec ( new Runnable()
                {
                    @Override
                    public void run()
                    {
                        setVersionNetStatus();
                        composite_header.layout();
                    }

                } );

            }

        }, 0, 5L * 60L * 1000L );

    }


    @Override
    public void updateStatus ( String st )
    {
        if ( splash != null )
        {
            try
            {
                splash.setVersion ( st );
            }

            catch ( Exception e )
            {
                e.printStackTrace();
            }

        }

    }

    // Periodic GUI update thread
    private PeriodicGuiUpdateThread periodicUpdateThread;
    // Window state
    private boolean windowMaximized = false;
    private Rectangle windowBounds = null;

    public static Rectangle getWindowBounds ( )
    {
        Properties p = Wrapper.loadExistingProps();

        String sx = p.getProperty ( Wrapper.PROP_WINDOW_X );
        String sy = p.getProperty ( Wrapper.PROP_WINDOW_Y );
        String sw = p.getProperty ( Wrapper.PROP_WINDOW_WIDTH );
        String sh = p.getProperty ( Wrapper.PROP_WINDOW_HEIGHT );

        if ( sx == null || sy == null || sw == null || sh == null )
        {
            return null;
        }

        try
        {
            int x = Integer.parseInt ( sx );
            int y = Integer.parseInt ( sy );
            int width = Integer.parseInt ( sw );
            int height = Integer.parseInt ( sh );
            return new Rectangle ( x, y, width, height );
        }

        catch ( NumberFormatException e )
        {
            return null;
        }

    }

    public static void saveWindowBounds ( Rectangle bounds )
    {
        Properties p = Wrapper.loadExistingProps();

        p.setProperty ( Wrapper.PROP_WINDOW_X, Integer.toString ( bounds.x ) );
        p.setProperty ( Wrapper.PROP_WINDOW_Y, Integer.toString ( bounds.y ) );
        p.setProperty ( Wrapper.PROP_WINDOW_WIDTH, Integer.toString ( bounds.width ) );
        p.setProperty ( Wrapper.PROP_WINDOW_HEIGHT, Integer.toString ( bounds.height ) );

        Wrapper.savePropsFile ( p );
    }

    public static boolean getWindowIsMaximized ( )
    {
        Properties p = Wrapper.loadExistingProps();

        boolean maximized = false;
        String m = p.getProperty ( Wrapper.PROP_WINDOW_MAXIMIZED );

        if ( m != null && m.equals ( "true" ) )
        {
            maximized = true;
        }

        return maximized;
    }

    public static void saveWindowIsMaximized ( boolean m )
    {
        Properties p = Wrapper.loadExistingProps();

        p.setProperty ( Wrapper.PROP_WINDOW_MAXIMIZED, Boolean.toString ( m ) );

        Wrapper.savePropsFile ( p );
    }


    public void startNode()
    {
        // new RawNet ( new File ( nodeDir ) )
        Properties p = getI2PReady();

        startNodeThread ( p );

    }

    /**
        Open the window.
    */
    public void open()
    {
        startNode();

        Display.setAppName ( "aktie" );
        Display display = Display.getDefault();

        windowBounds = getWindowBounds();
        windowMaximized = getWindowIsMaximized();

        createContents();
        shell.setVisible ( false );
        shell.open();
        shell.layout();

        // set the window to the size and position we had last time before closing
        if ( windowBounds != null )
        {
            shell.setBounds ( windowBounds );
        }

        // maximize the window if it was maximized the last time before closing
        shell.setMaximized ( windowMaximized );
        shell.addListener ( SWT.Resize,  new Listener ( )
        {
            @Override
            public void handleEvent ( Event e )
            {
                if ( shell.isDisposed() )
                {
                    return;
                }

                // remember the bounds in non-maximized window state
                windowMaximized = shell.getMaximized();

                if ( !windowMaximized )
                {
                    windowBounds = shell.getBounds ( );
                }

            }

        } );

        shell.setVisible ( true );

        splash = new SWTSplash ( shell );
        //startNode();
        splash.open();
        splash.setVersion ( Wrapper.VERSION );

        periodicUpdateThread = new PeriodicGuiUpdateThread ( this );

        while ( !shell.isDisposed() )
        {
            if ( !display.readAndDispatch() )
            {
                display.sleep();
            }

        }

        animator.stop();
        this.periodicUpdateThread.stop();

        // save the window state upon closing
        if ( windowBounds != null )
        {
            saveWindowBounds ( windowBounds );
        }

        saveWindowIsMaximized ( windowMaximized );

        System.out.println ( "CLOSING NODE" );
        node.closeNode();
    }

    private void addData ( CObj co )
    {
        SubTreeModel prov = ( SubTreeModel ) identTreeViewer.getContentProvider();
        prov.update ( co );
        identTreeViewer.setInput ( "Here is some data" );
        identModel.setCollaspseState ( identTreeViewer );

        pmTab.update ( co );

        splash.reallyClose();

    }

    /**
        Method invoked by PostsTable
    */
    public void postSearch ( )
    {
        postsTable.searchAndSort();
    }

    /**
        Method invoked by FilesTable
    */
    private void filesSearch()
    {
        filesTable.searchAndSort();
    }

    private void setShares ( String comid, String memid )
    {
        List<DirectoryShare> lst = node.getNode().getShareManager().listShares ( comid, memid );
        downloadToShareDialog.setShares ( lst );
        List<DirectoryShare> plst = new LinkedList<DirectoryShare>();
        DirectoryShare alls = new DirectoryShare();
        alls.setId ( -1 );
        alls.setShareName ( "All" );
        plst.add ( alls );
        plst.addAll ( lst );
        comboShareNameViewer.setInput ( plst );
        shareComboViewer.setInput ( lst );
        textShareName.setText ( "" );
        textSharePath.setText ( "" );
        textNumberSubDirs.setText ( "" );
        textNumberFiles.setText ( "" );
        btnDefaultDownloadLocation.setSelection ( false );
        btnDoNotGenerate.setSelection ( false );
        selectedShare = null;
        selectedDirectoryShare = null;
        ISelection isel = shareComboViewer.getSelection();
        ISelection isel2 = comboShareNameViewer.getSelection();

        if ( isel.isEmpty() )
        {
            if ( lst.size() > 0 )
            {
                selectedShare = lst.get ( 0 );
                StructuredSelection ss = new StructuredSelection ( selectedShare );
                shareComboViewer.setSelection ( ss );
            }

        }

        if ( isel2.isEmpty() )
        {
            if ( plst.size() > 0 )
            {
                selectedDirectoryShare = plst.get ( 0 );
                StructuredSelection ss = new StructuredSelection ( selectedDirectoryShare );
                comboShareNameViewer.setSelection ( ss );
            }

        }

        updateShareCount();
    }

    private void updateShareCount()
    {
        if ( selectedShare != null )
        {
            DirectoryShare ds = node.getNode().getShareManager().getShare ( selectedShare.getId() );
            textNumberFiles.setText ( Long.toString ( ds.getNumberFiles() ) );
            textNumberSubDirs.setText ( Long.toString ( ds.getNumberSubFolders() ) );
            textShareName.setText ( ds.getShareName() );
            textSharePath.setText ( ds.getDirectory() );
            btnDefaultDownloadLocation.setSelection ( ds.isDefaultDownload() );
            btnDoNotGenerate.setSelection ( ds.isSkipSpam() );
        }

    }

    private Text bannerText;

    public DirectoryShare getSelecedDirectoryShare()
    {
        return selectedDirectoryShare;
    }

    private void updateMembership()
    {
        membershipsTable.searchAndSort();
    }

    protected void createDialogs()
    {
        newCommunityDialog = new NewCommunityDialog ( shell, this );
        newCommunityDialog.create();
        newIdentityDialog = new NewIdentityDialog ( shell, this );
        newIdentityDialog.create();
        subscriptionDialog = new SubscriptionDialog ( shell, this );
        subscriptionDialog.create();
        newMemberDialog = new NewMemberDialog ( shell, this );
        newMemberDialog.create();
        newPostDialog = new NewPostDialog ( shell, this );
        newPostDialog.create();
        downloadPriorityDialog = new DownloadPriorityDialog ( shell, this );
        downloadPriorityDialog.create();
        downloadsTable.getTableViewer().setInput ( node.getNode().getFileHandler() );
        membersDialog = new ShowMembersDialog ( shell, this );
        membersDialog.create();
        privComDialog = new ShowPrivateCommunityDialog ( shell, this );
        privComDialog.create();
        shareDialog = new NewDirectoryShareDialog ( shell, this );
        shareDialog.create();
        downloadToShareDialog = new DownloadToShareDialog ( shell, this );
        downloadToShareDialog.create();
        addFolderDialog = new AddFolderDialog ( shell, this );
        addFolderDialog.create();
        advancedDialog = new AdvancedSearchDialog ( shell, this );
        advancedDialog.create();
        userRankDialog = new SetUserRankDialog ( shell, this );
        userRankDialog.create();
        hasFileDialog = new ShowHasFileDialog ( shell, userRankDialog, this );
        hasFileDialog.create();
        zeroDialog = new ZeroIdentityDialog ( shell, userRankDialog, this );
        zeroDialog.create();
        spamDialog = new SpamRankDialog ( shell );
        spamDialog.create();
        threadDialog = new PaymentThreadsDialog ( shell );
        threadDialog.create();
        prvMsgDialog = new PrivateMessageDialog ( this );
        prvMsgDialog.create();
        pmTab.setMessageDialog ( prvMsgDialog );
        privComDialog.setMessageDialog ( prvMsgDialog );
        launcherDialog = new LauncherDialog ( shell, this );
        launcherDialog.create();
        connectionDialog = new ConnectionDialog ( shell, this );
        connectionDialog.create();
        devIdentDialog = new SelectIdentityDialog ( shell, this, new IdentitySelectedInterface()
        {

            @Override
            public void selectedIdentity ( CObj i )
            {
                if ( i != null && selectedIdentity != null )
                {
                    boolean asure = MessageDialog.openConfirm ( shell, "New Developer?",
                                    "Make " + i.getDisplayName() + " a developer???" );

                    if ( asure )
                    {
                        CObj nd = new CObj();
                        nd.setType ( CObj.DEVELOPER );
                        nd.pushString ( CObj.IDENTITY, i.getId() );
                        nd.pushString ( CObj.CREATOR, selectedIdentity.getId() );
                        node.getNode().enqueue ( nd );
                    }

                }

            }

        } );

        devIdentDialog.create();
        updateMembership();
    }

    private void exportCommunities()
    {
        if ( exportCommunitiesFile != null )
        {
            try
            {
                File exf = new File ( exportCommunitiesFile );
                CObjList pubcoms = node.getNode().getIndex().getPublicCommunities();
                PrintWriter pw = new PrintWriter ( new FileOutputStream ( exf ) );

                for ( int c = 0; c < pubcoms.size(); c++ )
                {
                    CObj i = pubcoms.get ( c );
                    JSONObject jo = i.getJSON();
                    jo.write ( pw );
                    pw.println();
                }

                pw.close();
                pubcoms.close();
            }

            catch ( Exception e )
            {
                e.printStackTrace();
            }

        }

    }

    public static int MAXIMGWIDTH = 400;
    private boolean previewResize = true;

    private HH2Session session;

    private Composite composite_6;
    private MembershipsTable membershipsTable;
    private Composite composite_header;
    private Label lblError;

    private Text textShareName;
    private Text textSharePath;
    private Text textNumberSubDirs;
    private Text textNumberFiles;
    private Combo shareCombo;
    private ComboViewer shareComboViewer;
    private DirectoryShare selectedShare;
    private DirectoryShare selectedDirectoryShare;
    private Combo comboShareName;
    private ComboViewer comboShareNameViewer;
    private Button btnDefaultDownloadLocation;
    private Button btnDoNotGenerate;
    private Text txtAShareIs;

    private Button btnPost;
    private MenuItem mntmReply;
    private MenuItem mntmCreatePost;
    private Button btnAddFiles;
    private Button btnShare;
    private Button btnDelete;

    private boolean doDownloadLrg ( CObj c )
    {
        String lrgfile = c.getString ( CObj.NAME );

        if ( lrgfile != null && selectedIdentity != null )
        {
            CObj p = new CObj();
            p.setType ( CObj.USR_DOWNLOAD_FILE );
            p.pushString ( CObj.CREATOR, selectedIdentity.getId() );
            p.pushString ( CObj.NAME, c.getString ( CObj.NAME ) );
            p.pushString ( CObj.COMMUNITYID, c.getString ( CObj.COMMUNITYID ) );
            p.pushNumber ( CObj.FILESIZE, c.getNumber ( CObj.FILESIZE ) );
            p.pushString ( CObj.FRAGDIGEST, c.getString ( CObj.FRAGDIGEST ) );
            p.pushNumber ( CObj.FRAGSIZE, c.getNumber ( CObj.FRAGSIZE ) );
            p.pushNumber ( CObj.FRAGNUMBER, c.getNumber ( CObj.FRAGNUMBER ) );
            p.pushString ( CObj.FILEDIGEST, c.getString ( CObj.FILEDIGEST ) );
            p.pushString ( CObj.SHARE_NAME, c.getString ( CObj.SHARE_NAME ) );
            node.getNode().enqueue ( p );
            return true;
        }

        return false;
    }

    private boolean doDownloadPrv ( CObj c )
    {
        String lrgfile = c.getString ( CObj.PRV_NAME );

        if ( lrgfile != null && selectedIdentity != null )
        {
            CObj p = new CObj();
            p.setType ( CObj.USR_DOWNLOAD_FILE );
            p.pushString ( CObj.CREATOR, selectedIdentity.getId() );
            p.pushString ( CObj.COMMUNITYID, c.getString ( CObj.COMMUNITYID ) );
            p.pushString ( CObj.NAME, c.getString ( CObj.PRV_NAME ) );
            p.pushNumber ( CObj.FILESIZE, c.getNumber ( CObj.PRV_FILESIZE ) );
            p.pushString ( CObj.FRAGDIGEST, c.getString ( CObj.PRV_FRAGDIGEST ) );
            p.pushNumber ( CObj.FRAGSIZE, c.getNumber ( CObj.PRV_FRAGSIZE ) );
            p.pushNumber ( CObj.FRAGNUMBER, c.getNumber ( CObj.PRV_FRAGNUMBER ) );
            p.pushString ( CObj.FILEDIGEST, c.getString ( CObj.PRV_FILEDIGEST ) );
            p.pushString ( CObj.SHARE_NAME, c.getString ( CObj.SHARE_NAME ) );
            node.getNode().enqueue ( p );
            return true;
        }

        return false;
    }

    protected void downloadLargeFile ( CObj c )
    {
        if ( !doDownloadLrg ( c ) )
        {
            doDownloadPrv ( c );
        }

    }

    protected void downloadPreview ( CObj c )
    {
        if ( !doDownloadPrv ( c ) )
        {
            doDownloadLrg ( c );
        }

    }

    public static ImageRegistry imgReg;
    private Button btnEnableShareManager;
    private Label lblNotRunning;

    public CObj getAdvancedPostsQuery()
    {
        return advQuery;
    }

    public void setAdvancedQuery ( CObj q )
    {
        advQuery = q;
        postsSearchText.setText ( "" );
        postSearch();
    }

    private ImageAnimator animator = new ImageAnimator ( this );

    public ImageAnimator getAnimator()
    {
        return animator;
    }

    /**
        Create contents of the window.
    */
    protected void createContents()
    {
        if ( SWT.getPlatform().equals ( "cocoa" ) )
        {
            new CocoaUIEnhancer().earlyStartup();
        }

        imgReg = new ImageRegistry();

        shell = new Shell();
        shell.setSize ( 750, 550 );

        try
        {
            Image icons[] = new Image[5];
            ClassLoader loader = SWTApp.class.getClassLoader();

            InputStream is16  = loader.getResourceAsStream ( "images/aktie16.png" );
            icons[0] = new Image ( Display.getDefault(), is16 );
            is16.close();

            InputStream is32  = loader.getResourceAsStream ( "images/aktie32.png" );
            icons[1] = new Image ( Display.getDefault(), is32 );
            is32.close();

            InputStream is64  = loader.getResourceAsStream ( "images/aktie64.png" );
            icons[2] = new Image ( Display.getDefault(), is64 );
            is64.close();

            InputStream is128  = loader.getResourceAsStream ( "images/aktie128.png" );
            icons[3] = new Image ( Display.getDefault(), is128 );
            is128.close();

            InputStream is  = loader.getResourceAsStream ( "images/aktie.png" );
            icons[4] = new Image ( Display.getDefault(), is );
            is.close();

            shell.setImages ( icons );

            InputStream pi  = loader.getResourceAsStream ( "images/icons/user.png" );
            Image pimg = new Image ( Display.getDefault(), pi );
            pi.close();
            imgReg.put ( "identity", pimg );

            pi  = loader.getResourceAsStream ( "images/icons/database.png" );
            pimg = new Image ( Display.getDefault(), pi );
            pi.close();
            imgReg.put ( "pubsub", pimg );

            pi  = loader.getResourceAsStream ( "images/icons/key.png" );
            pimg = new Image ( Display.getDefault(), pi );
            pi.close();
            imgReg.put ( "privsub", pimg );

            pi  = loader.getResourceAsStream ( "images/icons/folder.png" );
            pimg = new Image ( Display.getDefault(), pi );
            pi.close();
            imgReg.put ( "folder", pimg );

            pi  = loader.getResourceAsStream ( "images/icons/bullet-red.png" );
            pimg = new Image ( Display.getDefault(), pi );
            pi.close();
            imgReg.put ( "offline", pimg );

            pi  = loader.getResourceAsStream ( "images/icons/bullet-green.png" );
            pimg = new Image ( Display.getDefault(), pi );
            pi.close();
            imgReg.put ( "online", pimg );

        }

        catch ( Exception e )
        {
            e.printStackTrace();
        }

        shell.setText ( "aktie" );
        shell.setLayout ( new GridLayout ( 1, false ) );

        Menu menu = new Menu ( shell, SWT.BAR );
        shell.setMenuBar ( menu );

        MenuItem mntmFile = new MenuItem ( menu, SWT.CASCADE );
        mntmFile.setText ( "File" );

        Menu menu_1 = new Menu ( mntmFile );
        mntmFile.setMenu ( menu_1 );

        doUpgrade = Wrapper.getAutoUpdate();

        final MenuItem launchItem = new MenuItem ( menu_1, SWT.NONE );
        launchItem.setText ( "Set Launcher Programs" );
        launchItem.addSelectionListener ( new SelectionListener()
        {

            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                launcherDialog.open();
            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        final MenuItem mntmAutoupdate = new MenuItem ( menu_1, SWT.NONE );
        mntmAutoupdate.setText ( doUpgrade ? "Disable auto upgrade" : "Enable auto upgrade" );
        mntmAutoupdate.addSelectionListener ( new SelectionListener()
        {

            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                doUpgrade = !doUpgrade;
                Wrapper.saveAutoUpdate ( doUpgrade );
                mntmAutoupdate.setText ( doUpgrade ? "Disable auto upgrade" : "Enable auto upgrade" );
            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        MenuItem mntmLoadSeedFile = new MenuItem ( menu_1, SWT.NONE );
        mntmLoadSeedFile.setText ( "Load Seed File" );
        mntmLoadSeedFile.addSelectionListener ( new LoadSeeds() );

        MenuItem mntmSaveSeedFile = new MenuItem ( menu_1, SWT.NONE );
        mntmSaveSeedFile.setText ( "Save Seed File" );
        mntmSaveSeedFile.addSelectionListener ( new SaveSeeds() );

        MenuItem mntmShowlocked = new MenuItem ( menu_1, SWT.NONE );
        mntmShowlocked.setText ( "Show Locked Communities" );
        mntmShowlocked.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                privComDialog.open();
            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        //zeroDialog
        MenuItem mntmZero = new MenuItem ( menu_1, SWT.NONE );
        mntmZero.setText ( "Show Zero Rank Identities" );
        mntmZero.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                zeroDialog.open();
            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        MenuItem mntmThread = new MenuItem ( menu_1, SWT.NONE );
        mntmThread.setText ( "Set Threads for Spam Payment" );
        mntmThread.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                threadDialog.open();
            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        MenuItem mntmSpam = new MenuItem ( menu_1, SWT.NONE );
        mntmSpam.setText ( "Set 'Not Spam' Rank" );
        mntmSpam.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                spamDialog.open();
            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        //zeroDialog
        MenuItem mntmLdSpamEx = new MenuItem ( menu_1, SWT.NONE );
        mntmLdSpamEx.setText ( "Load Spam Exception File" );
        mntmLdSpamEx.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                FileDialog fd = new FileDialog ( shell, SWT.OPEN );
                fd.setText ( "Open" );
                //fd.setFilterPath();
                String[] filterExt = { "*.*" };

                fd.setFilterExtensions ( filterExt );
                String selected = fd.open();

                if ( selected != null && node != null )
                {
                    node.loadSpamEx ( new File ( selected ) );
                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        MenuItem mntmI2Popts = new MenuItem ( menu_1, SWT.NONE );
        mntmI2Popts.setText ( "I2P Options" );
        mntmI2Popts.addSelectionListener ( new SelectionListener()
        {

            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                i2pDialog.open();
            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        //zeroDialog
        MenuItem mntmExit = new MenuItem ( menu_1, SWT.NONE );
        mntmExit.setText ( "Exit" );
        mntmExit.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                shell.dispose();

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        if ( Wrapper.getIsDeveloper() )
        {
            MenuItem mntmDumpOpen = new MenuItem ( menu_1, SWT.NONE );
            mntmDumpOpen.setText ( "Dump Open Searchers (debug)" );
            mntmDumpOpen.addSelectionListener ( new SelectionListener()
            {
                @Override
                public void widgetSelected ( SelectionEvent e )
                {
                    CObjList.displayAllStillOpen();
                }

                @Override
                public void widgetDefaultSelected ( SelectionEvent e )
                {
                }

            } );

            MenuItem mntmNewdev = new MenuItem ( menu_1, SWT.NONE );
            mntmNewdev.setText ( "NEW DEVELOPER" );
            mntmNewdev.addSelectionListener ( new SelectionListener()
            {
                @Override
                public void widgetSelected ( SelectionEvent e )
                {
                    if ( selectedIdentity != null )
                    {
                        boolean asure = MessageDialog.openConfirm ( shell, "New Developer?", "Are you sure?" );

                        if ( asure )
                        {
                            devIdentDialog.open();
                        }

                    }

                }

                @Override
                public void widgetDefaultSelected ( SelectionEvent e )
                {
                }

            } );

            MenuItem mntmSpamEx = new MenuItem ( menu_1, SWT.NONE );
            mntmSpamEx.setText ( "GEN SPAM EX" );
            mntmSpamEx.addSelectionListener ( new SelectionListener()
            {
                @Override
                public void widgetSelected ( SelectionEvent e )
                {
                    if ( selectedIdentity != null )
                    {
                        boolean asure = MessageDialog.openConfirm ( shell, "Update", "SAVE SPAM EX?" );
                        generateSpamEx ( selectedIdentity, asure );
                    }

                }

                @Override
                public void widgetDefaultSelected ( SelectionEvent e )
                {
                }

            } );

            MenuItem mntmSaveSpamEx = new MenuItem ( menu_1, SWT.NONE );
            mntmSaveSpamEx.setText ( "SAVE SPAM EX" );
            mntmSaveSpamEx.addSelectionListener ( new SelectionListener()
            {
                @Override
                public void widgetSelected ( SelectionEvent e )
                {
                    if ( selectedIdentity != null )
                    {
                        FileDialog fd = new FileDialog ( shell, SWT.SAVE );
                        fd.setText ( "Save" );
                        //fd.setFilterPath();
                        String[] filterExt = { "*" };

                        fd.setFilterExtensions ( filterExt );
                        String selected = fd.open();

                        if ( node != null && selected != null )
                        {
                            try
                            {
                                PrintWriter pw = new PrintWriter ( new FileOutputStream ( new File ( selected ) ) );
                                CObjList ilst = node.getNode().getIndex().getAllSpamEx ( selectedIdentity.getId() );

                                for ( int c = 0; c < ilst.size(); c++ )
                                {
                                    CObj i = ilst.get ( c );
                                    JSONObject jo = i.getJSON();
                                    jo.write ( pw );
                                    pw.println();
                                }

                                ilst.close();
                                pw.close();
                            }

                            catch ( Exception ex )
                            {

                            }

                        }

                    }

                }

                @Override
                public void widgetDefaultSelected ( SelectionEvent e )
                {
                }

            } );

        }

        composite_header = new Composite ( shell, SWT.NONE );
        composite_header.setLayout ( new GridLayout ( 5, false ) );
        composite_header.setLayoutData ( new GridData ( SWT.FILL, SWT.CENTER, false, false, 1, 1 ) );

        lblVersion = new Label ( composite_header, SWT.NONE );
        lblVersion.setLayoutData ( new GridData ( SWT.FILL, SWT.CENTER, true, false, 1, 1 ) );
        setVersionNetStatus();

        lblSpeed = new Label ( composite_header, SWT.NONE );
        lblSpeed.setLayoutData ( new GridData ( SWT.FILL, SWT.CENTER, true, false, 1, 1 ) );
        updateConnections ( 0L, 0L, 1L ); // use update method to initialize text of speed label

        lblError = new Label ( composite_header, SWT.NONE );
        lblError.setLayoutData ( new GridData ( SWT.FILL, SWT.CENTER, false, false, 1, 1 ) );
        lblError.setText ( "" );

        lblNotRunning = new Label ( composite_header, SWT.NONE );
        lblNotRunning.setText ( "Share Manager" );

        btnEnableShareManager = new Button ( composite_header, SWT.CHECK );
        btnEnableShareManager.setText ( "Enabled" );
        new Label ( composite_header, SWT.NONE );
        new Label ( composite_header, SWT.NONE );
        new Label ( composite_header, SWT.NONE );
        new Label ( composite_header, SWT.NONE );
        new Label ( composite_header, SWT.NONE );
        btnEnableShareManager.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                boolean en = btnEnableShareManager.getSelection();
                //CObj ce = new CObj();
                //ce.setType(CObj.USR_SHARE_MGR);
                //ce.pushString(CObj.ENABLED, Boolean.toString(en));
                //node.enqueue(ce);
                Wrapper.saveEnabledShareManager ( en );
                node.getNode().getShareManager().setEnabled ( en );
            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        TabFolder tabFolder = new TabFolder ( shell, SWT.NONE );
        tabFolder.setLayoutData ( new GridData ( SWT.FILL, SWT.FILL, true, true, 1, 1 ) );

        TabItem tbtmCommunity = new TabItem ( tabFolder, SWT.NONE );
        tbtmCommunity.setText ( "Communities" );

        Composite composite = new Composite ( tabFolder, SWT.NONE );
        tbtmCommunity.setControl ( composite );
        composite.setLayout ( new FillLayout ( SWT.HORIZONTAL ) );

        SashForm sashForm = new SashForm ( composite, SWT.NONE );

        SashForm sashForm2 = new SashForm ( sashForm, SWT.VERTICAL );

        Composite composite_1 = new Composite ( sashForm2, SWT.NONE );
        composite_1.setLayout ( new FillLayout ( SWT.VERTICAL ) );

        identTreeViewer = new TreeViewer ( composite_1, SWT.BORDER | SWT.H_SCROLL | SWT.V_SCROLL );
        identTree = identTreeViewer.getTree();

        identTreeViewer.addSelectionChangedListener ( new ISelectionChangedListener()
        {
            @SuppressWarnings ( "rawtypes" )
            @Override
            public void selectionChanged ( SelectionChangedEvent s )
            {
                IStructuredSelection sel = ( IStructuredSelection ) s.getSelection();

                CObj id = null;
                CObj com = null;

                Iterator i = sel.iterator();

                if ( i.hasNext() )
                {
                    Object selo = i.next();

                    if ( selo instanceof SubTreeEntity )
                    {
                        SubTreeEntity sm = ( SubTreeEntity ) selo;
                        CObj co = identModel.getCObj ( sm.getId() );

                        if ( co != null )
                        {

                            if ( CObj.IDENTITY.equals ( co.getType() ) )
                            {
                                id = co;
                            }

                            if ( CObj.COMMUNITY.equals ( co.getType() ) )
                            {
                                com = co;
                                id = identModel.getCObj ( sm.getIdentity() );
                            }

                        }

                    }

                }

                if ( id != null )
                {
                    selectedIdentity = id;

                    if ( com != null )
                    {
                        setSelected ( id, com );
                    }

                }


            }

        } );

        Menu menu_2 = new Menu ( identTree );
        identTree.setMenu ( menu_2 );

        MenuItem mntmSubscribe_1 = new MenuItem ( menu_2, SWT.NONE );
        mntmSubscribe_1.setText ( "Subscribe" );
        mntmSubscribe_1.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                if ( selectedIdentity != null )
                {
                    subscriptionDialog.open ( selectedIdentity.getId() );
                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        MenuItem mntmUnsubscribe = new MenuItem ( menu_2, SWT.NONE );
        mntmUnsubscribe.setText ( "Unsubscribe" );
        mntmUnsubscribe.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                IStructuredSelection sel = ( IStructuredSelection ) identTreeViewer.getSelection();
                String selid = null;
                @SuppressWarnings ( "rawtypes" )
                Iterator i = sel.iterator();

                if ( i.hasNext() && selid == null )
                {
                    Object selo = i.next();

                    if ( selo instanceof SubTreeEntity )
                    {
                        SubTreeEntity ts = ( SubTreeEntity ) selo;

                        if ( SubTreeEntity.PRVCOMMUNITY_TYPE == ts.getType() ||
                                SubTreeEntity.PUBCOMMUNITY_TYPE == ts.getType() )
                        {
                            String identid = ts.getIdentity();
                            CObj com = identModel.getCObj ( ts.getRefId() );

                            if ( com != null )
                            {
                                String comid = com.getDig();
                                CObj unsub = new CObj();
                                unsub.setType ( CObj.SUBSCRIPTION );
                                unsub.pushString ( CObj.COMMUNITYID, comid );
                                unsub.pushString ( CObj.CREATOR, identid );
                                unsub.pushString ( CObj.SUBSCRIBED, "false" );
                                node.getNode().enqueue ( unsub );
                            }

                        }

                    }

                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        MenuItem mntmNewflder = new MenuItem ( menu_2, SWT.NONE );
        mntmNewflder.setText ( "New Folder" );
        mntmNewflder.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                IStructuredSelection sel = ( IStructuredSelection ) identTreeViewer.getSelection();
                String selid = null;
                @SuppressWarnings ( "rawtypes" )
                Iterator i = sel.iterator();

                if ( i.hasNext() && selid == null )
                {
                    Object selo = i.next();

                    if ( selo instanceof SubTreeEntity )
                    {
                        SubTreeEntity et = ( SubTreeEntity ) selo;
                        addFolderDialog.open ( et );
                    }

                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        MenuItem mntmRmFolder = new MenuItem ( menu_2, SWT.NONE );
        mntmRmFolder.setText ( "Remove Folder" );
        mntmRmFolder.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                IStructuredSelection sel = ( IStructuredSelection ) identTreeViewer.getSelection();
                String selid = null;
                @SuppressWarnings ( "rawtypes" )
                Iterator i = sel.iterator();

                if ( i.hasNext() && selid == null )
                {
                    Object selo = i.next();

                    if ( selo instanceof SubTreeEntity )
                    {
                        SubTreeEntity et = ( SubTreeEntity ) selo;
                        identModel.removeFolder ( et );
                        identTreeViewer.setInput ( "Folder removed" );
                        identModel.setCollaspseState ( identTreeViewer );
                    }

                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        MenuItem mntmShowmem = new MenuItem ( menu_2, SWT.NONE );
        mntmShowmem.setText ( "Show Members" );
        mntmShowmem.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                if ( selectedCommunity != null )
                {
                    membersDialog.open ( selectedCommunity );
                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        MenuItem mntmNewCommunity_1 = new MenuItem ( menu_2, SWT.NONE );
        mntmNewCommunity_1.setText ( "New Community" );
        mntmNewCommunity_1.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                IStructuredSelection sel = ( IStructuredSelection ) identTreeViewer.getSelection();
                String selid = null;
                @SuppressWarnings ( "rawtypes" )
                Iterator i = sel.iterator();

                if ( i.hasNext() && selid == null )
                {
                    Object selo = i.next();

                    if ( selo instanceof SubTreeEntity )
                    {
                        SubTreeEntity ti = ( SubTreeEntity ) selo;
                        selid = ti.getIdentity();
                    }

                }

                newCommunityDialog.open ( selid );
            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        MenuItem mntmNewIdentity_1 = new MenuItem ( menu_2, SWT.NONE );
        mntmNewIdentity_1.setText ( "New Identity" );
        mntmNewIdentity_1.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                newIdentityDialog.open();
            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        MenuItem mntmNewMember = new MenuItem ( menu_2, SWT.NONE );
        mntmNewMember.setText ( "New Member" );
        mntmNewMember.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                if ( selectedIdentity != null && selectedCommunity != null )
                {
                    newMemberDialog.open ( selectedIdentity.getId(), selectedCommunity.getDig() );
                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        MenuItem mntmRefetch = new MenuItem ( menu_2, SWT.NONE );
        mntmRefetch.setText ( "Re-Download All Data" );
        mntmRefetch.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                if ( selectedIdentity != null && selectedCommunity != null )
                {
                    CObj co = new CObj();
                    co.setType ( CObj.USR_COMMUNITY_UPDATE );
                    co.pushString ( CObj.COMMUNITYID, selectedCommunity.getDig() );
                    node.getNode().enqueue ( co );

                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        MenuItem mntmClose = new MenuItem ( menu_2, SWT.NONE );
        mntmClose.setText ( "Disconnect Identity" );
        mntmClose.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                IStructuredSelection sel = ( IStructuredSelection ) identTreeViewer.getSelection();
                CObj selid = null;
                @SuppressWarnings ( "rawtypes" )
                Iterator i = sel.iterator();

                if ( i.hasNext() && selid == null )
                {
                    Object selo = i.next();

                    if ( selo instanceof SubTreeEntity )
                    {
                        SubTreeEntity ti = ( SubTreeEntity ) selo;
                        selid = identModel.getCObj ( ti.getIdentity() );
                    }

                }

                if ( selid != null )
                {
                    CObj cl = selid.clone();
                    cl.setType ( CObj.USR_START_DEST );
                    cl.pushPrivateNumber ( CObj.PRV_DEST_OPEN, 0L );

                    if ( node != null )
                    {
                        node.getNode().priorityEnqueue ( cl );
                    }

                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        MenuItem mntmConnect = new MenuItem ( menu_2, SWT.NONE );
        mntmConnect.setText ( "Connect Identity" );
        mntmConnect.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                IStructuredSelection sel = ( IStructuredSelection ) identTreeViewer.getSelection();
                CObj selid = null;
                @SuppressWarnings ( "rawtypes" )
                Iterator i = sel.iterator();

                if ( i.hasNext() && selid == null )
                {
                    Object selo = i.next();

                    if ( selo instanceof SubTreeEntity )
                    {
                        SubTreeEntity ti = ( SubTreeEntity ) selo;
                        selid = identModel.getCObj ( ti.getIdentity() );
                    }


                }

                if ( selid != null )
                {
                    CObj cl = selid.clone();
                    cl.setType ( CObj.USR_START_DEST );
                    cl.pushPrivateNumber ( CObj.PRV_DEST_OPEN, 1L );

                    if ( node != null )
                    {
                        node.getNode().priorityEnqueue ( cl );
                    }

                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        membershipsTable = new MembershipsTable ( sashForm2, this );

        Menu menu_6 = new Menu ( membershipsTable.getTable() );
        membershipsTable.setMenu ( menu_6 );

        MenuItem mntmSubscribe = new MenuItem ( menu_6, SWT.NONE );
        mntmSubscribe.setText ( "Subscribe" );
        mntmSubscribe.addSelectionListener ( new SelectionListener()
        {

            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                IStructuredSelection sel = membershipsTable.getTableViewer().getSelection();
                @SuppressWarnings ( "rawtypes" )
                Iterator i = sel.iterator();

                if ( i.hasNext() )
                {
                    Object selo = i.next();

                    if ( selo instanceof CObjListArrayElement )
                    {
                        CObjListArrayElement ae = ( CObjListArrayElement ) selo;
                        CObj fr = ae.getCObj();

                        if ( fr != null )
                        {
                            String memid = null;
                            String comid = fr.getDig();
                            String creator = fr.getString ( CObj.CREATOR );
                            CObjList idlst = node.getNode().getIndex().getMyIdentities();

                            for ( int c = 0; c < idlst.size() && memid == null; c++ )
                            {
                                try
                                {
                                    String id = idlst.get ( c ).getId();

                                    if ( creator.equals ( id ) )
                                    {
                                        memid = id;
                                    }

                                }

                                catch ( Exception e2 )
                                {
                                    e2.printStackTrace();
                                }

                            }

                            idlst.close();

                            if ( memid == null )
                            {
                                CObjList mlst = node.getNode().getIndex().getMyMemberships ( comid );

                                if ( mlst.size() > 0 )
                                {
                                    try
                                    {
                                        CObj mm = mlst.get ( 0 );
                                        memid = mm.getPrivate ( CObj.MEMBERID );
                                    }

                                    catch ( Exception e2 )
                                    {
                                        e2.printStackTrace();
                                    }

                                }

                            }

                            if ( comid != null && memid != null )
                            {
                                //Create a subscription
                                CObj sub = new CObj();
                                sub.setType ( CObj.SUBSCRIPTION );
                                sub.pushString ( CObj.CREATOR, memid );
                                sub.pushString ( CObj.COMMUNITYID, comid );
                                sub.pushString ( CObj.SUBSCRIBED, "true" );
                                node.getNode().enqueue ( sub );

                            }

                        }

                    }

                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        MenuItem mntmShowPriv = new MenuItem ( menu_6, SWT.NONE );
        mntmShowPriv.setText ( "Show Locked Communities" );
        mntmShowPriv.addSelectionListener ( new SelectionListener()
        {

            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                privComDialog.open();
            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {

            }

        } );

        sashForm2.setWeights ( new int[] {1, 1} );


        //scrolledComposite.setContent ( identTree );
        //scrolledComposite.setMinSize ( identTree.computeSize ( SWT.DEFAULT, SWT.DEFAULT ) );

        Composite composite_2 = new Composite ( sashForm, SWT.NONE );
        GridLayout gl_composite_2 = new GridLayout ( 1, false );
        composite_2.setLayout ( gl_composite_2 );

        lblIdentCommunity = new Label ( composite_2, SWT.NONE );
        lblIdentCommunity.setLayoutData ( new GridData ( SWT.FILL, SWT.CENTER, true, false, 1, 1 ) );
        lblIdentCommunity.setText ( "Identity: <id>  Community: <com>" );

        TabFolder tabFolder_1 = new TabFolder ( composite_2, SWT.NONE );
        GridData gd_tabFolder_1 = new GridData ( SWT.FILL, SWT.FILL, true, true, 1, 1 );
        gd_tabFolder_1.heightHint = 235;
        tabFolder_1.setLayoutData ( gd_tabFolder_1 );

        TabItem tbtmPosts = new TabItem ( tabFolder_1, SWT.NONE );
        tbtmPosts.setText ( "Posts" );

        Composite composite_3 = new Composite ( tabFolder_1, SWT.NONE );
        tbtmPosts.setControl ( composite_3 );
        composite_3.setLayout ( new FillLayout ( SWT.HORIZONTAL ) );

        SashForm sashForm_1 = new SashForm ( composite_3, SWT.VERTICAL );

        Composite composite_5 = new Composite ( sashForm_1, SWT.NONE );
        composite_5.setLayout ( new BorderLayout ( 0, 0 ) );

        Composite composite_7 = new Composite ( composite_5, SWT.NONE );
        composite_7.setLayoutData ( BorderLayout.NORTH );
        composite_7.setLayout ( new GridLayout ( 7, false ) );
        new Label ( composite_7, SWT.NONE );

        btnPost = new Button ( composite_7, SWT.NONE );
        btnPost.setText ( "Post" );
        btnPost.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                if ( selectedIdentity != null && selectedCommunity != null )
                {
                    newPostDialog.open ( selectedIdentity, selectedCommunity, null, null );
                }

                else
                {
                    MessageDialog.openWarning ( shell, "Select a community.", "Sorry, you have to select the community you wish to post to." );
                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        Label label = new Label ( composite_7, SWT.SEPARATOR | SWT.VERTICAL );
        GridData gd_label = new GridData ( SWT.RIGHT, SWT.CENTER, false, false, 1, 1 );
        gd_label.heightHint = 25;
        label.setLayoutData ( gd_label );

        postsSearchText = new Text ( composite_7, SWT.BORDER );
        postsSearchText.setLayoutData ( new GridData ( SWT.FILL, SWT.CENTER, true, false, 1, 1 ) );
        postsSearchText.addListener ( SWT.Traverse, new Listener()
        {
            @Override
            public void handleEvent ( Event event )
            {
                if ( event.detail == SWT.TRAVERSE_RETURN )
                {
                    if ( selectedIdentity == null && selectedCommunity == null )
                    {
                        MessageDialog.openWarning ( shell, "Select a community.", "Sorry, you have to select a community first." );
                    }

                    else
                    {
                        advQuery = null;
                        postSearch();
                    }

                }

            }

        } );

        Button btnSearch = new Button ( composite_7, SWT.NONE );
        btnSearch.setLayoutData ( new GridData ( SWT.RIGHT, SWT.CENTER, false, false, 1, 1 ) );
        btnSearch.setText ( "Search" );
        btnSearch.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                if ( selectedIdentity == null && selectedCommunity == null )
                {
                    MessageDialog.openWarning ( shell, "Select a community.", "Sorry, you have to select a community first." );
                }

                else
                {

                    String st = postsSearchText.getText();
                    Matcher m = Pattern.compile ( "(\\S+)" ).matcher ( st );

                    if ( m.find() )
                    {
                        advQuery = null;
                    }

                    postSearch();
                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        Button btnAdvanced = new Button ( composite_7, SWT.NONE );
        btnAdvanced.setLayoutData ( new GridData ( SWT.RIGHT, SWT.CENTER, false, false, 1, 1 ) );
        btnAdvanced.setText ( "Advanced" );
        btnAdvanced.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                if ( selectedIdentity == null && selectedCommunity == null )
                {
                    MessageDialog.openWarning ( shell, "Select a community.", "Sorry, you have to select a community first." );
                }

                else
                {
                    advancedDialog.open ( selectedCommunity, selectedIdentity );
                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        postsTable = new PostsTable ( composite_5, this );
        postsTable.setLayoutData ( BorderLayout.CENTER );

        Menu menu_5 = new Menu ( postsTable.getTable() );
        postsTable.setMenu ( menu_5 );

        MenuItem mntmOpen = new MenuItem ( menu_5, SWT.NONE );
        mntmOpen.setText ( "Open" );
        mntmOpen.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                if ( selectedIdentity != null )
                {
                    IStructuredSelection sel = postsTable.getTableViewer().getSelection();

                    @SuppressWarnings ( "rawtypes" )
                    Iterator i = sel.iterator();

                    if ( i.hasNext() )
                    {
                        Object selo = i.next();

                        if ( selo instanceof CObjListArrayElement )
                        {
                            CObjListArrayElement ae = ( CObjListArrayElement ) selo;
                            CObj fr = ae.getCObj();
                            String lf = fr.getPrivate ( CObj.LOCALFILE );

                            if ( !launcherDialog.open ( lf ) )
                            {
                                MessageDialog.openWarning ( shell, "No program selected file extension.", "Sorry, you haven't set a launcher for this file type." );
                            }

                        }

                    }

                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        MenuItem mntmDownloadFile2 = new MenuItem ( menu_5, SWT.NONE );
        mntmDownloadFile2.setText ( "Download File(s)" );
        mntmDownloadFile2.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                if ( selectedIdentity != null )
                {
                    IStructuredSelection sel = postsTable.getTableViewer().getSelection();

                    @SuppressWarnings ( "rawtypes" )
                    Iterator i = sel.iterator();

                    while ( i.hasNext() )
                    {
                        Object selo = i.next();

                        if ( selo instanceof CObjListArrayElement )
                        {
                            CObjListArrayElement ae = ( CObjListArrayElement ) selo;
                            CObj fr = ae.getCObj();
                            downloadLargeFile ( fr );
                        }

                    }

                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        MenuItem mntmDownloadPrv = new MenuItem ( menu_5, SWT.NONE );
        mntmDownloadPrv.setText ( "Download Preview(s)" );
        mntmDownloadPrv.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                if ( selectedIdentity != null )
                {
                    IStructuredSelection sel = postsTable.getTableViewer().getSelection();

                    @SuppressWarnings ( "rawtypes" )
                    Iterator i = sel.iterator();

                    while ( i.hasNext() )
                    {
                        Object selo = i.next();

                        if ( selo instanceof CObjListArrayElement )
                        {
                            CObjListArrayElement ae = ( CObjListArrayElement ) selo;
                            CObj fr = ae.getCObj();
                            downloadPreview ( fr );
                        }

                    }

                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        MenuItem mntmDownloadFile22 = new MenuItem ( menu_5, SWT.NONE );
        mntmDownloadFile22.setText ( "Download File(s) to Share.." );
        mntmDownloadFile22.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                if ( selectedIdentity != null )
                {
                    IStructuredSelection sel = postsTable.getTableViewer().getSelection();

                    downloadToShareDialog.open ( sel, false );
                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        MenuItem mntmDownloadPrv2 = new MenuItem ( menu_5, SWT.NONE );
        mntmDownloadPrv2.setText ( "Download Preview(s) to Share.." );
        mntmDownloadPrv2.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                if ( selectedIdentity != null )
                {
                    IStructuredSelection sel = postsTable.getTableViewer().getSelection();

                    downloadToShareDialog.open ( sel, true );
                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        MenuItem mntmShowHas = new MenuItem ( menu_5, SWT.NONE );
        mntmShowHas.setText ( "Who Has File" );
        mntmShowHas.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                if ( selectedIdentity != null )
                {
                    IStructuredSelection sel = postsTable.getTableViewer().getSelection();

                    @SuppressWarnings ( "rawtypes" )
                    Iterator i = sel.iterator();

                    while ( i.hasNext() )
                    {
                        Object selo = i.next();

                        if ( selo instanceof CObjListArrayElement )
                        {
                            CObjListArrayElement ae = ( CObjListArrayElement ) selo;
                            CObj fr = ae.getCObj();
                            hasFileDialog.open ( fr );
                        }

                    }

                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        MenuItem mntmSetrank = new MenuItem ( menu_5, SWT.NONE );
        mntmSetrank.setText ( "Set user rank" );
        mntmSetrank.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                if ( selectedIdentity != null )
                {
                    IStructuredSelection sel = postsTable.getTableViewer().getSelection();

                    @SuppressWarnings ( "rawtypes" )
                    Iterator i = sel.iterator();

                    Set<String> userids = new HashSet<String>();

                    while ( i.hasNext() )
                    {
                        Object selo = i.next();

                        if ( selo instanceof CObjListArrayElement )
                        {
                            CObjListArrayElement ae = ( CObjListArrayElement ) selo;
                            CObj fr = ae.getCObj();
                            String id = fr.getString ( CObj.CREATOR );

                            if ( id != null )
                            {
                                userids.add ( id );
                            }

                        }

                    }

                    Set<CObj> users = new HashSet<CObj>();

                    for ( String id : userids )
                    {
                        CObj u = node.getNode().getIndex().getIdentity ( id );
                        users.add ( u );
                    }

                    if ( users.size() > 0 )
                    {
                        userRankDialog.open ( users );
                    }

                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        mntmReply = new MenuItem ( menu_5, SWT.NONE );
        mntmReply.setText ( "Reply" );
        mntmReply.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                if ( selectedIdentity != null )
                {
                    IStructuredSelection sel = postsTable.getTableViewer().getSelection();

                    @SuppressWarnings ( "rawtypes" )
                    Iterator i = sel.iterator();

                    while ( i.hasNext() )
                    {
                        Object selo = i.next();

                        if ( selo instanceof CObjListArrayElement )
                        {
                            CObjListArrayElement ae = ( CObjListArrayElement ) selo;
                            CObj pst = ae.getCObj();
                            newPostDialog.reply ( selectedIdentity, selectedCommunity, pst );

                        }

                    }

                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        MenuItem prvReply = new MenuItem ( menu_5, SWT.NONE );
        prvReply.setText ( "Send Private Msg" );
        prvReply.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                if ( selectedIdentity != null )
                {
                    IStructuredSelection sel = postsTable.getTableViewer().getSelection();

                    @SuppressWarnings ( "rawtypes" )
                    Iterator i = sel.iterator();

                    while ( i.hasNext() )
                    {
                        Object selo = i.next();

                        if ( selo instanceof CObjListArrayElement )
                        {
                            CObjListArrayElement ae = ( CObjListArrayElement ) selo;
                            CObj pst = ae.getCObj();
                            String pr = pst.getString ( CObj.CREATOR );
                            String ps = selectedIdentity.getId();

                            if ( pr != null && ps != null )
                            {
                                prvMsgDialog.open ( ps, pr, pst );
                            }

                        }

                    }

                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        composite_6 = new Composite ( sashForm_1, SWT.NONE );
        composite_6.setLayout ( new GridLayout() );

        postText = new StyledText ( composite_6, SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL | SWT.MULTI
                                    | SWT.NO_REDRAW_RESIZE | SWT.NO_BACKGROUND );

        postText.setFont ( JFaceResources.getFont ( JFaceResources.TEXT_FONT ) );

        postText.setLayoutData ( new GridData ( SWT.FILL, SWT.FILL, true, true ) );
        postText.setEditable ( false );
        postText.setCaret ( null );

        // use a verify listener to dispose the images
        postText.addVerifyListener ( new VerifyListener()
        {
            public void verifyText ( VerifyEvent event )
            {
                if ( event.start == event.end ) { return; }

                String text = postText.getText(); //getText(event.start, event.end - 1);
                int index = text.length() - 1;
                StyleRange style = postText.getStyleRangeAtOffset ( index );

                if ( style != null )
                {
                    //Image image = ( Image ) style.data;

                    //if ( image != null ) { image.dispose(); }

                }

            }

        } );

        // draw images on paint event
        postText.addPaintObjectListener ( new PaintObjectListener()
        {
            @Override
            public void paintObject ( PaintObjectEvent event )
            {
                StyleRange style = event.style;
                ImageLoader image = ( ImageLoader ) style.data;

                int w = image.data[0].width;
                int h = image.data[0].height;

                int x = event.x; // + MARGIN;
                int y = event.y; // + event.ascent - 2 * h / 3;

                int sw = w;
                int sh = h;

                if ( previewResize )
                {
                    if ( sw > MAXIMGWIDTH )
                    {
                        sh = sh * MAXIMGWIDTH / sw;
                        sw = MAXIMGWIDTH;
                    }

                }

                animator.update ( image, x, y, sw, sh );

            }

        } );

        postText.addListener ( SWT.Dispose, new Listener()
        {
            public void handleEvent ( Event event )
            {
                StyleRange[] styles = postText.getStyleRanges();

                for ( int i = 0; i < styles.length; i++ )
                {
                    StyleRange style = styles[i];

                    if ( style.data != null )
                    {
                        //Image image = ( Image ) style.data;

                        //if ( image != null ) { image.dispose(); }

                    }

                }

            }

        } );

        animator.create();

        sashForm_1.setWeights ( new int[] {1, 1} );

        TabItem tbtmFiles = new TabItem ( tabFolder_1, SWT.NONE );
        tbtmFiles.setText ( "Files" );

        Composite composite_4 = new Composite ( tabFolder_1, SWT.NONE );
        tbtmFiles.setControl ( composite_4 );
        composite_4.setLayout ( new BorderLayout ( 0, 0 ) );

        Composite composite_9 = new Composite ( composite_4, SWT.NONE );
        composite_9.setLayoutData ( BorderLayout.NORTH );
        composite_9.setLayout ( new GridLayout ( 6, false ) );

        btnAddFiles = new Button ( composite_9, SWT.NONE );
        btnAddFiles.setText ( "Add File(s)" );
        btnAddFiles.addSelectionListener ( new AddFile() );

        Label label_1 = new Label ( composite_9, SWT.SEPARATOR | SWT.VERTICAL );
        GridData gd_label_1 = new GridData ( SWT.RIGHT, SWT.CENTER, false, false, 1, 1 );
        gd_label_1.heightHint = 25;
        label_1.setLayoutData ( gd_label_1 );

        comboShareNameViewer = new ComboViewer ( composite_9, SWT.NONE | SWT.READ_ONLY );
        comboShareNameViewer.setContentProvider ( new DirectoryShareContentProvider() );
        comboShareNameViewer.setLabelProvider ( new DirectoryShareLabelProvider() );
        comboShareName = comboShareNameViewer.getCombo();
        comboShareName.setLayoutData ( new GridData ( SWT.FILL, SWT.CENTER, true, false, 1, 1 ) );
        comboShareName.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                IStructuredSelection sel = ( IStructuredSelection ) comboShareNameViewer.getSelection();
                @SuppressWarnings ( "rawtypes" )
                Iterator i = sel.iterator();

                if ( i.hasNext() )
                {
                    DirectoryShare sh = ( DirectoryShare ) i.next();
                    selectedDirectoryShare = sh;
                    filesSearch();
                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        filesSearchText = new Text ( composite_9, SWT.BORDER );
        filesSearchText.setLayoutData ( new GridData ( SWT.FILL, SWT.CENTER, true, false, 1, 1 ) );
        filesSearchText.addListener ( SWT.Traverse, new Listener()
        {
            @Override
            public void handleEvent ( Event event )
            {
                if ( event.detail == SWT.TRAVERSE_RETURN )
                {
                    if ( selectedIdentity == null && selectedCommunity == null )
                    {
                        MessageDialog.openWarning ( shell, "Select a community.", "Sorry, you have to select a community first." );
                    }

                    else
                    {
                        filesSearch();
                    }

                }

            }

        } );


        Button btnSearch_1 = new Button ( composite_9, SWT.NONE );
        btnSearch_1.setText ( "Search" );
        btnSearch_1.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                if ( selectedCommunity != null )
                {
                    filesSearch();
                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );


        sashForm.setWeights ( new int[] {1, 4} );

        filesTable = new FilesTable ( composite_4, this );
        filesTable.setLayoutData ( BorderLayout.CENTER );

        Menu menu_3 = new Menu ( filesTable.getTable() );
        filesTable.setMenu ( menu_3 );

        MenuItem mntmFileOpen = new MenuItem ( menu_3, SWT.NONE );
        mntmFileOpen.setText ( "Open" );
        mntmFileOpen.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                if ( selectedIdentity != null )
                {
                    IStructuredSelection sel = filesTable.getTableViewer().getSelection();

                    @SuppressWarnings ( "rawtypes" )
                    Iterator i = sel.iterator();

                    while ( i.hasNext() )
                    {
                        Object selo = i.next();

                        if ( selo instanceof CObjListArrayElement )
                        {
                            CObjListArrayElement ae = ( CObjListArrayElement ) selo;
                            CObj fr = ae.getCObj();
                            String lf = fr.getString ( CObj.LOCALFILE );

                            if ( !launcherDialog.open ( lf ) )
                            {
                                MessageDialog.openWarning ( shell, "No program selected file extension.", "Sorry, you haven't set a launcher for this file type." );
                            }

                        }

                    }

                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        MenuItem mntmDownloadFile = new MenuItem ( menu_3, SWT.NONE );
        mntmDownloadFile.setText ( "Download File" );
        mntmDownloadFile.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                if ( selectedIdentity != null )
                {
                    IStructuredSelection sel = filesTable.getTableViewer().getSelection();

                    @SuppressWarnings ( "rawtypes" )
                    Iterator i = sel.iterator();

                    while ( i.hasNext() )
                    {
                        Object selo = i.next();

                        if ( selo instanceof CObjListArrayElement )
                        {
                            CObjListArrayElement ae = ( CObjListArrayElement ) selo;
                            CObj fr = ae.getCObj();
                            fr.setType ( CObj.USR_DOWNLOAD_FILE );
                            fr.pushString ( CObj.CREATOR, selectedIdentity.getId() );
                            node.getNode().enqueue ( fr );
                        }

                    }

                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        MenuItem mntmDownloadFile3 = new MenuItem ( menu_3, SWT.NONE );
        mntmDownloadFile3.setText ( "Download File to share.." );
        mntmDownloadFile3.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                if ( selectedIdentity != null )
                {
                    IStructuredSelection sel = filesTable.getTableViewer().getSelection();

                    downloadToShareDialog.open ( sel, true );
                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        mntmCreatePost = new MenuItem ( menu_3, SWT.NONE );
        mntmCreatePost.setText ( "Attach to Post" );
        mntmCreatePost.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                if ( selectedIdentity != null && selectedCommunity != null )
                {
                    CObj f1 = null;
                    CObj f2 = null;
                    IStructuredSelection sel = filesTable.getTableViewer().getSelection();
                    @SuppressWarnings ( "rawtypes" )
                    Iterator i = sel.iterator();

                    while ( i.hasNext() )
                    {
                        Object selo = i.next();

                        if ( selo instanceof CObjListArrayElement )
                        {
                            CObjListArrayElement ae = ( CObjListArrayElement ) selo;
                            CObj fr = ae.getCObj();

                            if ( f2 == null )
                            {
                                f2 = fr;
                            }

                            else
                            {
                                long f2size = f2.getNumber ( CObj.FILESIZE );
                                long frsize = fr.getNumber ( CObj.FILESIZE );

                                if ( f2size < frsize )
                                {
                                    f1 = f2;
                                    f2 = fr;
                                }

                                else
                                {
                                    f1 = fr;
                                }

                            }

                        }

                    }

                    if ( f2 != null )
                    {
                        newPostDialog.open ( selectedIdentity, selectedCommunity, f1, f2 );
                    }

                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        MenuItem mntmShowhas = new MenuItem ( menu_3, SWT.NONE );
        mntmShowhas.setText ( "Who Has File" );
        mntmShowhas.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                if ( selectedCommunity != null )
                {
                    IStructuredSelection sel = filesTable.getTableViewer().getSelection();
                    @SuppressWarnings ( "rawtypes" )
                    Iterator i = sel.iterator();

                    if ( i.hasNext() )
                    {
                        Object selo = i.next();

                        if ( selo instanceof CObjListArrayElement )
                        {
                            CObjListArrayElement ae = ( CObjListArrayElement ) selo;
                            CObj fr = ae.getCObj();
                            hasFileDialog.open ( fr );
                        }

                    }

                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );


        //============================================================================================
        TabItem tbtmShare = new TabItem ( tabFolder_1, SWT.NONE );
        tbtmShare.setText ( "Shares" );

        Composite composite_14 = new Composite ( tabFolder_1, SWT.NONE );
        tbtmShare.setControl ( composite_14 );
        composite_14.setLayout ( new GridLayout ( 2, false ) );

        btnShare = new Button ( composite_14, SWT.NONE );
        btnShare.setText ( "Add a directory to share" );
        btnShare.addSelectionListener ( new SelectionListener()
        {

            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                if ( selectedIdentity != null && selectedCommunity != null )
                {
                    shareDialog.open ( selectedIdentity, selectedCommunity );
                    setShares ( selectedCommunity.getDig(), selectedIdentity.getId() );

                }

                else
                {
                    MessageDialog.openWarning ( shell, "Select a community.", "Sorry, you have to select the community you wish to share with." );
                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        shareComboViewer = new ComboViewer ( composite_14, SWT.NONE | SWT.READ_ONLY );
        shareComboViewer.setContentProvider ( new DirectoryShareContentProvider() );
        shareComboViewer.setLabelProvider ( new DirectoryShareLabelProvider() );
        shareCombo = shareComboViewer.getCombo();
        shareCombo.setLayoutData ( new GridData ( SWT.FILL, SWT.CENTER, true, false, 1, 1 ) );
        shareCombo.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                IStructuredSelection sel = ( IStructuredSelection ) shareComboViewer.getSelection();
                @SuppressWarnings ( "rawtypes" )
                Iterator i = sel.iterator();

                if ( i.hasNext() )
                {
                    DirectoryShare sh = ( DirectoryShare ) i.next();
                    selectedShare = sh;
                    updateShareCount();
                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        Label lblName = new Label ( composite_14, SWT.NONE );
        lblName.setLayoutData ( new GridData ( SWT.RIGHT, SWT.CENTER, false, false, 1, 1 ) );
        lblName.setText ( "Name" );

        textShareName = new Text ( composite_14, SWT.BORDER );
        textShareName.setLayoutData ( new GridData ( SWT.FILL, SWT.CENTER, true, false, 1, 1 ) );
        textShareName.setEditable ( false );

        Label lblPath = new Label ( composite_14, SWT.NONE );
        lblPath.setLayoutData ( new GridData ( SWT.RIGHT, SWT.CENTER, false, false, 1, 1 ) );
        lblPath.setText ( "Path" );

        textSharePath = new Text ( composite_14, SWT.BORDER );
        textSharePath.setLayoutData ( new GridData ( SWT.FILL, SWT.CENTER, true, false, 1, 1 ) );
        textSharePath.setEditable ( false );

        Label lblNumberOfSubdirectories = new Label ( composite_14, SWT.NONE );
        lblNumberOfSubdirectories.setLayoutData ( new GridData ( SWT.RIGHT, SWT.CENTER, false, false, 1, 1 ) );
        lblNumberOfSubdirectories.setText ( "Number of Subdirectories" );

        textNumberSubDirs = new Text ( composite_14, SWT.BORDER );
        textNumberSubDirs.setLayoutData ( new GridData ( SWT.FILL, SWT.CENTER, true, false, 1, 1 ) );
        textNumberSubDirs.setEditable ( false );

        Label lblNumberOfFiles = new Label ( composite_14, SWT.NONE );
        lblNumberOfFiles.setLayoutData ( new GridData ( SWT.RIGHT, SWT.CENTER, false, false, 1, 1 ) );
        lblNumberOfFiles.setText ( "Number of files" );

        textNumberFiles = new Text ( composite_14, SWT.BORDER );
        textNumberFiles.setLayoutData ( new GridData ( SWT.FILL, SWT.CENTER, true, false, 1, 1 ) );
        textNumberFiles.setEditable ( false );
        new Label ( composite_14, SWT.NONE );

        new Label ( composite_14, SWT.NONE );

        btnDefaultDownloadLocation = new Button ( composite_14, SWT.CHECK );
        btnDefaultDownloadLocation.setText ( "Default download location" );
        btnDefaultDownloadLocation.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                if ( selectedShare != null )
                {
                    node.getNode().getShareManager().addShare (
                        selectedShare.getCommunityId(),
                        selectedShare.getMemberId(),
                        selectedShare.getShareName(),
                        selectedShare.getDirectory(),
                        btnDefaultDownloadLocation.getSelection(),
                        btnDoNotGenerate.getSelection() );

                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        btnDoNotGenerate = new Button ( composite_14, SWT.CHECK );
        btnDoNotGenerate.setText ( "Do not generate anti-spam (Expert)" );
        new Label ( composite_14, SWT.NONE );

        btnDoNotGenerate.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                if ( selectedShare != null )
                {
                    node.getNode().getShareManager().addShare (
                        selectedShare.getCommunityId(),
                        selectedShare.getMemberId(),
                        selectedShare.getShareName(),
                        selectedShare.getDirectory(),
                        btnDefaultDownloadLocation.getSelection(),
                        btnDoNotGenerate.getSelection() );

                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );


        btnDelete = new Button ( composite_14, SWT.NONE );
        btnDelete.setText ( "Delete" );
        btnDelete.addSelectionListener ( new SelectionListener()
        {
            @Override
            public void widgetSelected ( SelectionEvent e )
            {
                if ( selectedShare != null )
                {
                    node.getNode().getShareManager().deleteShare ( selectedShare.getCommunityId(),
                            selectedShare.getMemberId(), selectedShare.getShareName() );

                    if ( selectedCommunity != null && selectedIdentity != null )
                    {
                        setShares ( selectedCommunity.getDig(), selectedIdentity.getId() );
                    }

                }

            }

            @Override
            public void widgetDefaultSelected ( SelectionEvent e )
            {
            }

        } );

        new Label ( composite_14, SWT.NONE );

        txtAShareIs = new Text ( composite_14, SWT.MULTI | SWT.BORDER | SWT.WRAP | SWT.V_SCROLL );
        txtAShareIs.setEditable ( false );
        txtAShareIs.setText ( "A 'Share' is a directory or folder on your system, where all "
                              + "files are automatically shared with the community. "
                              + "Any new files copied into the directory will automatically "
                              + "be shared. You can move and rename files within a share, "
                              + "and other users will still be able to download these.  "
                              + "You can also download new files into your 'Share' directories." );
        txtAShareIs.setLayoutData ( new GridData ( SWT.FILL, SWT.CENTER, true, false, 1, 1 ) );


        TabItem tbtmPM = new TabItem ( tabFolder, SWT.NONE );
        tbtmPM.setText ( "Private Messages" );

        pmTab = new PMTab ( tabFolder, SWT.NONE, this );
        tbtmPM.setControl ( pmTab );

        TabItem tbtmDownloadds = new TabItem ( tabFolder, SWT.NONE );
        tbtmDownloadds.setText ( "Downloads" );

        Composite composite_10 = new Composite ( tabFolder, SWT.NONE );
        tbtmDownloadds.setControl ( composite_10 );
        composite_10.setLayout ( new BorderLayout ( 0, 0 ) );


        downloadsTable = new DownloadsTable ( composite_10, this );
        downloadsTable.setLayoutData ( BorderLayout.CENTER );

        TabItem tbtmConnections = new TabItem ( tabFolder, SWT.NONE );
        tbtmConnections.setText ( "Connections" );

        Composite composite_8 = new Composite ( tabFolder, SWT.NONE );
        tbtmConnections.setControl ( composite_8 );
        composite_8.setLayout ( new FillLayout ( SWT.HORIZONTAL ) );

        connectionTable = new ConnectionTable ( composite_8, this );

        bannerText = new Text ( shell, SWT.BORDER );
        bannerText.setEditable ( false );
        bannerText.setLayoutData ( new GridData ( SWT.FILL, SWT.BOTTOM, true, false, 1, 1 ) );
        bannerText.setText ( Wrapper.getLastDevMessage() );

    }

    public Tree getIdentTree()
    {
        return identTree;
    }

    public Label getLblIdentCommunity()
    {
        return lblIdentCommunity;
    }

    public Text getPostsSearchText()
    {
        return postsSearchText;
    }

    public StyledText getPostText()
    {
        return postText;
    }

    public ConnectionDialog getConnectionDialog()
    {
        return connectionDialog;
    }

    public Text getFilesSearchText()
    {
        return filesSearchText;
    }

    public Text getBannerText()
    {
        return bannerText;
    }

    public Label getLblVersion()
    {
        return lblVersion;
    }

    public Label getLblError()
    {
        return lblError;
    }

    public Combo getShareCombo()
    {
        return shareCombo;
    }

    public ComboViewer getShareComboViewer()
    {
        return shareComboViewer;
    }

    public Combo getComboShareName()
    {
        return comboShareName;
    }

    public ComboViewer getComboShareNameViewer()
    {
        return comboShareNameViewer;
    }

    public Button getBtnDefaultDownloadLocation()
    {
        return btnDefaultDownloadLocation;
    }

    public Button getBtnEnableShareManager()
    {
        return btnEnableShareManager;
    }

    public Label getLblShareMgrRunning()
    {
        return lblNotRunning;
    }

    public ConnectionCallback getConnectionCallback()
    {
        return concallback;
    }

    public void updateConnections ( long deltaInBytes, long deltaOutBytes, long deltaTime )
    {
        String inkBps = String.format ( "%.2f", deltaInBytes / 1.024 / deltaTime );
        String outkBps = String.format ( "%.2f", deltaOutBytes / 1.024 / deltaTime );
        lblSpeed.setText ( new StringBuffer().append ( "Down: " ).append ( inkBps ).append ( " kB/s | Up: " ).append ( outkBps ).append ( " kB/s" ).toString() );

        if ( connectionTable != null )
        {
            connectionTable.getTableViewer().setInput ( concallback );
        }

    }

    public DownloadPriorityDialog getDownloadPriorityDialog()
    {
        return downloadPriorityDialog;
    }

    public ShowHasFileDialog getShowHasFileDialog()
    {
        return hasFileDialog;
    }

    public Shell getShell()
    {
        return shell;
    }

    public void togglePreviewResize()
    {
        previewResize = !previewResize;
    }

    public void setPreviewResize ( boolean b )
    {
        previewResize = b;
    }

    @Override
    public boolean doUpgrade()
    {
        return doUpgrade;
    }

    @Override
    public void updateMessage ( final String msg )
    {
        Display.getDefault().asyncExec ( new Runnable()
        {
            @Override
            public void run()
            {
                lblVersion.setText ( msg );
            }

        } );

    }

}
