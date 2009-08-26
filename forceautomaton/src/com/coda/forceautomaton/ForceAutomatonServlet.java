package com.coda.forceautomaton;

import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.appengine.api.memcache.Expiration;
import com.google.appengine.api.memcache.MemcacheService;
import com.google.appengine.api.memcache.MemcacheServiceFactory;
import com.google.wave.api.AbstractRobotServlet;
import com.google.wave.api.Blip;
import com.google.wave.api.Event;
import com.google.wave.api.EventType;
import com.google.wave.api.RobotMessageBundle;
import com.google.wave.api.TextView;
import com.google.wave.api.Wavelet;
import com.sforce.soap.partner.Connector;
import com.sforce.soap.partner.PartnerConnection;
import com.sforce.soap.partner.QueryResult;
import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.ConnectionException;
import com.sforce.ws.ConnectorConfig;

/**
 * Retrieve Accounts into a Google Wave! 
 * 
 * @author Andrew Fawcett
 */
@SuppressWarnings("serial")
public class ForceAutomatonServlet extends AbstractRobotServlet 
{
	private static final Logger LOG = Logger.getLogger(ForceAutomatonServlet.class.getName());
	
	/**
	 * Regular expression to parse a blip for an account name reference
	 */
	private static final Pattern REGEXP_ACCOUNT = 
		Pattern.compile(
				"(" + 
					"([aA]ccount '[A-Z][\\w\\s]*')|([aA]ccount ([A-Z][\\w]*\\s*)+)" + 
				"|" + 
					"('[A-Z][\\w\\s]*' [aA]ccount)|(([A-Z][\\w]*\\s*)+ account)" +
				")");
	
	/**
	 * Handle Wave events!
	 */
	@Override
	public void processEvents(RobotMessageBundle bundle) 
	{
		try
		{
			if (bundle.wasSelfAdded()) 
			{
				Wavelet wavelet = bundle.getWavelet();				
				Blip blip = wavelet.appendBlip();
				TextView textView = blip.getDocument();
				textView.append("ForceAutomaton online!");
			}
			
			for (Event e: bundle.getEvents()) 
			{
				if(e.getType() == EventType.BLIP_SUBMITTED)
				{								
					String blipText = e.getBlip().getDocument().getText();
					ArrayList<String> accountNames = parseAccounts(blipText);
					if(accountNames.size()>0)
					{
						LOG.info("Found accounts " + accountNames);
						Blip blip = e.getBlip().createChild();
						TextView textView = blip.getDocument();
						PartnerConnection connection = getConnected();
						for(String accountName : accountNames)
						{
							String escapedAccountName = 
								accountName.replaceAll("'", "\\\\'");
							String soql = 
								"Select a.Name, " + 
									"a.AnnualRevenue, " +
									"a.BillingCity, " +
									"a.BillingCountry, " +
									"a.BillingPostalCode, " +
									"a.BillingState, " + 
									"a.BillingStreet, " + 
									"a.Fax, " + 
									"a.Phone, " + 
									"a.Website " + 
								"from Account a " + 
								"where a.Name Like '%" + accountName + "%' limit 1";							
							QueryResult resultSet = connection.query(soql);
							SObject[] records = resultSet.getRecords();
							if(records.length==1)
							{
								SObject record = records[0];
								textView.append(record.getField("Name")+"\n");
								textView.append(record.getField("BillingStreet").toString()+"\n");
								textView.append(record.getField("BillingCity").toString()+",");
								textView.append(record.getField("BillingState").toString()+" ");
								textView.append(record.getField("BillingPostalCode").toString()+"\n");
								textView.append(record.getField("BillingCountry").toString()+"\n");
								textView.append("Phone: " + record.getField("Phone")+"\n");
								textView.append("Website: " + record.getField("Website")+"\n");
							}
							else
							{
								textView.append("I am not able to find account '"+accountName+"'");
							}
						}
					}
					else
					{
						LOG.info("Not found accounts in '" + blipText + "'");
					}
				}
			}
		}
		catch (Exception ex)
		{
			// Dump exception to the Wave for now
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			ex.printStackTrace(pw);
			pw.close();
			Wavelet wavelet = bundle.getWavelet();				
			Blip blip = wavelet.appendBlip();
			TextView textView = blip.getDocument();
			textView.append(ex.getMessage() + "\n" + sw.getBuffer().toString());
			// Log exception
			LOG.log(Level.SEVERE, "Unexpected error occured.", ex);
		}
	}

	/**
	 * Uses user and password from web.xml (Session ID cached for 60 minutes)
	 * @return
	 * @throws ConnectionException
	 */
	private PartnerConnection getConnected() throws ConnectionException
	{
		PartnerConnection connection = null;
		String userName = getServletContext().getInitParameter("salesforce.username");
		String password = getServletContext().getInitParameter("salesforce.password");		
		MemcacheService memCache = MemcacheServiceFactory.getMemcacheService();
		String key = userName+password;
		if(memCache.contains(key))
		{
			Session session = (Session) memCache.get(key);
			ConnectorConfig config = new ConnectorConfig();
			config.setSessionId(session.SessionId);
			config.setServiceEndpoint(session.ServerURL);
			connection = Connector.newConnection(config);
		}
		else
		{
			ConnectorConfig config = new ConnectorConfig();
			config.setUsername(userName);
			config.setPassword(password);
			connection = Connector.newConnection(config);
			Session session = new Session();
			session.SessionId = connection.getConfig().getSessionId();
			session.ServerURL = connection.getConfig().getServiceEndpoint();
			memCache.put(key, session, Expiration.byDeltaSeconds(60 * 60)); // 60 minutes
		}
		return connection;
	}
	
	/**
	 * Cached Force.com session
	 */
	public static class Session implements Serializable
	{
		public String SessionId;
		public String ServerURL;
	}
	
	/**
	 * Parses for references to accounts
	 * @param input
	 * @return
	 */
    private static ArrayList<String> parseAccounts(String input)
    {
    	ArrayList<String> list = new ArrayList<String>();
    	System.out.println();
    	System.out.println("'" + input + "'");
        Matcher matcher = REGEXP_ACCOUNT.matcher(input);
        while (matcher.find()) 
        {
            String account = matcher.group();
            account = account.replaceAll("[aA]ccount", "");
            account = account.replace('\'', ' ');
            account = account.trim();
            list.add(account);
        }
        return list;
    }	
}
