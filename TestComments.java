import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import java.lang.reflect.Method;
public class TestComments {
    public static void main(String[] args) throws Exception {
        for(Method m : CommentsInfoItem.class.getMethods()) {
            System.out.println(m.getName());
        }
    }
}
