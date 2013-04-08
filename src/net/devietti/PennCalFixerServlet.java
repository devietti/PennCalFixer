package net.devietti;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Enumeration;
import javax.servlet.http.*;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.filter.Filters;
import org.jdom2.input.SAXBuilder;
import org.jdom2.xpath.XPathExpression;
import org.jdom2.xpath.XPathFactory;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.CalendarOutputter;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.*;
import net.fortuna.ical4j.model.component.VTimeZone;
import net.fortuna.ical4j.model.property.Location;
import net.fortuna.ical4j.model.property.Uid;

@SuppressWarnings( "serial" )
public class PennCalFixerServlet extends HttpServlet {

   private PrintWriter         err;
   private HttpServletResponse rsp;

   private void debug(String s) {
      err.println(s);
   }

   private void error(String s) {
      rsp.setContentType("text/plain");
      err.println(s);
      throw new IllegalStateException(s);
   }

   public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {

      err = resp.getWriter();
      rsp = resp;

      final String base = "http://www.upenn.edu/calendar-export/?";
      final String gaeURL = "http://penncalfix.appspot.com/penncalfixer";

      if ( !req.getParameterNames().hasMoreElements() ) {
         resp.setContentType("text/plain");
         debug("No valid arguments specified. Usage: " + gaeURL + "?ARGS");
         debug("E.g., " + gaeURL + "?school=4&owner=15&showndays=200");
         debug("See http://www.upenn.edu/calendar-export/?type=sample for details on valid args.");
         return;
      }

      String xmlURL = base;
      for ( Enumeration<String> en = req.getParameterNames(); en.hasMoreElements(); ) {
         String k = en.nextElement();
         xmlURL += k + "=" + req.getParameter(k);
         if ( en.hasMoreElements() ) xmlURL += "&";
      }

      final String icalURL = xmlURL + "&type=ical2";

      //debug(xmlURL);
      //debug(icalURL);

      // Create a TimeZone
      final TimeZoneRegistry registry = TimeZoneRegistryFactory.getInstance().createRegistry();
      final TimeZone timezone = registry.getTimeZone("America/New_York");
      final VTimeZone tz = timezone.getVTimeZone();

      // parse XML event list
      URLConnection yc;
      final Calendar cal;
      try {
         yc = new URL(xmlURL).openConnection();
      } catch ( MalformedURLException e ) {
         error(e.getMessage());
         return;
      }
      final Document doc = parseXML(yc.getInputStream());
      final XPathFactory xpfac = XPathFactory.instance();

      // parse iCal event list
      try {
         yc = new URL(icalURL).openConnection();
         cal = parseCal(yc.getInputStream());
      } catch ( MalformedURLException e ) {
         error(e.getMessage());
         return;
      }

      // walk over iCal event list, updating each event's location
      ComponentList events = cal.getComponents(Component.VEVENT);
      for ( Object o : events ) {
         final Component c = (Component) o;

         final Uid uid = (Uid) c.getProperty("UID");
         if ( uid == null ) continue;

         String u = uid.getValue();
         final int at = u.indexOf('@');
         u = u.substring(0, at >= 0 ? at : u.length());

         // look up actual location from the XML event
         final XPathExpression<Element> xp;
         // NB: only use first hit
         xp = xpfac.compile("/calendar/event/link[@id='" + u + "']/parent::*[1]", Filters.element());
         for ( Element e : xp.evaluate(doc) ) {
            if ( !e.getName().equals("event") ) continue;

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

      // output the updated iCal events
      resp.setContentType("text/calendar");
      CalendarOutputter outputter = new CalendarOutputter();
      outputter.setValidating(false);
      try {
         outputter.output(cal, resp.getWriter());
      } catch ( ValidationException e ) {
         error(e.getMessage());
      }

   }

   private Document parseXML(InputStream is) {
      SAXBuilder builder = new SAXBuilder();
      try {

         return builder.build(is);

      } catch ( IOException io ) {
         error(io.getMessage());
      } catch ( JDOMException jdomex ) {
         error(jdomex.getMessage());
      }
      return null;
   }

   private Calendar parseCal(InputStream is) {
      CalendarBuilder builder = new CalendarBuilder();
      try {

         return builder.build(is);

      } catch ( ParserException e ) {
         error(e.getMessage());
      } catch ( IOException e ) {
         error(e.getMessage());
      }
      return null;
   }

}
