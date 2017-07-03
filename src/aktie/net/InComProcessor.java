package aktie.net;

import java.util.Iterator;
import java.util.List;

import org.bouncycastle.crypto.params.KeyParameter;
import org.hibernate.Query;
import org.hibernate.Session;

import aktie.GenericProcessor;
import aktie.UpdateCallback;
import aktie.crypto.Utils;
import aktie.data.CObj;
import aktie.data.CommunityMyMember;
import aktie.data.HH2Session;
import aktie.index.Index;
import aktie.sequences.CommunitySequence;
import aktie.spam.SpamTool;
import aktie.user.IdentityManager;
import aktie.utils.DigestValidator;
import aktie.utils.SymDecoder;

public class InComProcessor extends GenericProcessor
{

    private UpdateCallback guicallback;
    private Index index;
    private HH2Session session;
    private DigestValidator validator;
    private SpamTool spamtool;
    private CObj ConId;
    private IdentityManager identManager;

    public InComProcessor ( HH2Session s, Index i, SpamTool st, IdentityManager im, CObj id, UpdateCallback cb )
    {
        index = i;
        session = s;
        guicallback = cb;
        spamtool = st;
        ConId = id;
        identManager = im;
        validator = new DigestValidator ( index, spamtool );
    }

    @SuppressWarnings ( "unchecked" )
    @Override
    public boolean process ( CObj b )
    {
        String type = b.getType();

        if ( CObj.COMMUNITY.equals ( type ) )
        {

            if ( validator.valid ( b ) )
            {
                boolean isnew = ( null == index.getByDig ( b.getDig() ) );

                Long seqnum = b.getNumber ( CObj.SEQNUM );
                String creatorid = b.getString ( CObj.CREATOR );
                b.pushPrivate ( CObj.MINE, "false" );

                if ( seqnum != null && creatorid != null )
                {

                    Session s = null;

                    try
                    {
                        //Find the last sequence number to set.
                        CommunitySequence comseq = new CommunitySequence ( session );
                        comseq.setId ( creatorid );
                        comseq.updateSequence ( b );

                        s = session.getSession();

                        if ( isnew )
                        {
                            String scope = b.getString ( CObj.SCOPE );

                            if ( CObj.SCOPE_PRIVATE.equals ( scope ) )
                            {
                                //Get my memberships, attempt to decode - in case
                                //we got our membership before the community data
                                Query q = s.createQuery ( "SELECT x FROM CommunityMyMember x" );
                                List<CommunityMyMember> mmlst = q.list();
                                boolean decoded = false;
                                CommunityMyMember themember = null;
                                Iterator<CommunityMyMember> i = mmlst.iterator();

                                while ( i.hasNext() && !decoded )
                                {
                                    CommunityMyMember cmm = i.next();
                                    KeyParameter sk = new KeyParameter ( cmm.getKey() );

                                    if ( SymDecoder.decode ( b, sk ) )
                                    {
                                        decoded = true;
                                        themember = cmm;
                                    }

                                }

                                if ( themember != null )
                                {
                                    b.pushPrivate ( CObj.KEY, Utils.toString ( themember.getKey() ) );
                                    b.pushPrivate ( CObj.MINE, "true" );
                                }

                            }

                            else
                            {
                                SymDecoder.decodeText ( b, b.getString ( CObj.PAYLOAD ), b.getString ( CObj.PAYLOAD2 ) );

                                if ( b.getPrivate ( CObj.NAME ) != null )
                                {
                                    b.pushPrivate ( CObj.MINE, "true" );
                                }

                            }

                            //Set the rank of the post based on the rank of the
                            //user
                            CObj idty = index.getIdentity ( creatorid );

                            if ( idty != null )
                            {
                                Long rnk = idty.getPrivateNumber ( CObj.PRV_USER_RANK );

                                if ( rnk != null )
                                {
                                    b.pushPrivateNumber ( CObj.PRV_USER_RANK, rnk );
                                }

                            }

                            if ( identManager != null && ConId != null )
                            {
                                long seq = identManager.getGlobalSequenceNumber ( ConId.getId(), false );
                                b.pushPrivateNumber ( CObj.getGlobalSeq ( ConId.getId() ), seq );
                            }

                            index.index ( b, true );
                            guicallback.update ( b );
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

                            }

                            catch ( Exception e2 )
                            {
                            }

                            try
                            {
                                s.close();
                            }

                            catch ( Exception e2 )
                            {
                            }

                        }

                    }

                }

            }

            return true;
        }

        return false;
    }

}
