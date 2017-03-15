package devietti;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.CalendarOutputter;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.*;
import net.fortuna.ical4j.model.component.VTimeZone;
import net.fortuna.ical4j.model.property.Location;
import net.fortuna.ical4j.model.property.Uid;
import net.fortuna.ical4j.validate.ValidationException;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.filter.Filters;
import org.jdom2.input.SAXBuilder;
import org.jdom2.xpath.XPathExpression;
import org.jdom2.xpath.XPathFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;

public class PennCalFixer implements RequestHandler<Void, Void> {

    private static final String S3_BUCKET = "penncalfixer";
    private static final String S3_KEY_ESE = "upenn-ese.ics";
    private static final String S3_KEY_CIS = "upenn-cis.ics";
    private static final int DEPT_ID_ESE = 115;
    private static final int DEPT_ID_CIS = 15;

    public static void main(String[] args) {
        generateFixedICal(DEPT_ID_ESE, S3_KEY_ESE);
        generateFixedICal(DEPT_ID_CIS, S3_KEY_CIS);
    }

    private static void generateFixedICal(final int deptId, final String s3Key) {
        // pull calendar starting 1 year ago today

        final java.util.Calendar juc = java.util.Calendar.getInstance();
        final int month = juc.get(java.util.Calendar.MONTH);
        final int year = juc.get(java.util.Calendar.YEAR) - 1;

        // NB: See http://www.upenn.edu/calendar-export/?type=sample for details on valid args.
        final String URL = String.format("http://www.upenn.edu/calendar-export/?school=4&owner=%d&showndays=500&year=%d&month=%d&day=1", deptId, year, month);
        final String xmlURL = URL + "&type=xml";
        final String icalURL = URL + "&type=ical2";

        // Create a TimeZone
        final TimeZoneRegistry registry = TimeZoneRegistryFactory.getInstance().createRegistry();
        final TimeZone timezone = registry.getTimeZone("America/New_York");
        final VTimeZone tz = timezone.getVTimeZone();

        URLConnection yc;
        final Calendar cal;
        try {

            // parse XML event list
            debug("Fetching " + xmlURL);
            yc = new URL(xmlURL).openConnection();
            final Document doc = parseXML(yc.getInputStream());
            final XPathFactory xpfac = XPathFactory.instance();

            // parse iCal event list
            debug("Fetching " + icalURL);
            yc = new URL(icalURL).openConnection();
            cal = parseCal(yc.getInputStream());

            // walk over iCal event list, updating each event's location
            ComponentList events = cal.getComponents(Component.VEVENT);
            for (Object o : events) {
                final Component c = (Component) o;

                final Uid uid = (Uid) c.getProperty("UID");
                if (uid == null) continue;

                String u = uid.getValue();
                final int at = u.indexOf('@');
                u = u.substring(0, at >= 0 ? at : u.length());

                // look up actual location from the XML event
                final XPathExpression<Element> xp;
                // NB: only use first hit
                xp = xpfac.compile("/calendar/event/link[@id='" + u + "']/parent::*[1]", Filters.element());
                for (Element e : xp.evaluate(doc)) {
                    if (!e.getName().equals("event")) continue;

                    Element loc = e.getChild("location");
                    Element room = e.getChild("room");
                    String fullLoc = (loc == null ? "" : loc.getText())
                            + (room == null ? "" : " " + room.getText());

                    // update iCal event
                    Property oldLoc = c.getProperties().getProperty("location");
                    c.getProperties().remove(oldLoc);
                    c.getProperties().add(new Location(fullLoc));

                    // add missing timezone information
                    c.getProperties().add(tz.getTimeZoneId());
                }

            }

            // output the updated iCal events into a static file in S3
            CalendarOutputter outputter = new CalendarOutputter();
            outputter.setValidating(false);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try {
                outputter.output(cal, baos);
            } catch (ValidationException e) {
                error(e.getMessage());
            }
            String icalString = baos.toString("UTF-8");
            debug("Uploading fixed iCal file to S3...");
            //debug(icalString);
            uploadToS3(icalString, s3Key);

        } catch (MalformedURLException e) {
            error(e.getMessage());
        } catch (IOException e) {
            error(e.getMessage());
        }
    }

    private static void uploadToS3(final String fileContents, final String s3Key) {
        try {
            final AmazonS3 s3 = new AmazonS3Client();
            s3.putObject(S3_BUCKET, s3Key, fileContents);
            debug("Finished uploading iCal file "+s3Key+" to S3");
        } catch (AmazonServiceException e) {
            error(e.getErrorMessage());
        } catch (AmazonClientException e) {
            error(e.getMessage());
        }
    }

    private static void debug(String s) {
        System.out.println(s);
    }

    // NB: on AWS Lambda, System.out/err go to CloudWatch logs

    private static void error(String s) {
        System.err.println(s);
        throw new IllegalStateException(s);
    }

    private static Document parseXML(InputStream is) {
        SAXBuilder builder = new SAXBuilder();
        try {

            return builder.build(is);

        } catch (IOException io) {
            error(io.getMessage());
        } catch (JDOMException jdomex) {
            error(jdomex.getMessage());
        }
        return null;
    }

    private static Calendar parseCal(InputStream is) {
        CalendarBuilder builder = new CalendarBuilder();
        try {

            return builder.build(is);

        } catch (ParserException e) {
            error(e.getMessage());
        } catch (IOException e) {
            error(e.getMessage());
        }
        return null;
    }

    public Void handleRequest(Void request, Context context) {
        generateFixedICal(DEPT_ID_ESE, S3_KEY_ESE);
        generateFixedICal(DEPT_ID_CIS, S3_KEY_CIS);
        return null;
    }

}
