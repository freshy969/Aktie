package aktie.net;

import aktie.GenericProcessor;
import aktie.data.CObj;
import aktie.index.CObjList;
import aktie.index.Index;

public class ReqIdentProcessor extends GenericProcessor
{

    private Index index;
    private ConnectionThread connection;

    public ReqIdentProcessor ( Index i )
    {
        index = i;
    }

    @Override
    public boolean process ( CObj b )
    {
        String type = b.getType();

        if ( CObj.CON_REQ_IDENTITIES.equals ( type ) )
        {
            if ( !connection.isFileMode() )
            {
                CObjList il = index.getIdentities();
                connection.enqueue ( il );
            }

            return true;
        }

        return false;
    }

    @Override
    public void setContext ( Object c )
    {
        connection = ( ConnectionThread ) c;

    }

}
