package jenkins.plugins;

import java.io.*;
import antlr.ANTLRException;
import hudson.model.Cause;
import net.sf.json.*;
import hudson.util.ListBoxModel;
import hudson.util.FormValidation;

import static hudson.Util.fixNull;
import hudson.Extension;
import hudson.model.SCMedItem;
import hudson.model.Item;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.RobustReflectionConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.Hashtable;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;

import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.reflection.PureJavaReflectionProvider;
import com.thoughtworks.xstream.mapper.Mapper;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;

public final class ObsEventTrigger extends Trigger<SCMedItem> {
    private static Hashtable metaData = new Hashtable();
    public static void addMetaData(String key, Object value) {
        metaData.put(key, value);
    }
    public static Object getMetaData(String key) {
        return metaData.get(key);
    }
    public static void removeMetaData(String key) {
        metaData.remove(key);
    }

    public class Cleaner extends Thread {
        private ObsEventTrigger trigger;
        public Cleaner(ObsEventTrigger ob) {
            trigger = ob;
        }
        public void run() {
            trigger._stop();
        }
    }
    private transient Cleaner cleaner;
    
    private final String obs_event;
    private final String amqp_server;
    private JSONObject obMatchEvent;
    private JSONObject obGotEvent;
    private JSONArray sMatchNames;
    private Channel channel;
    private String obs_queue;
    private String logging;
    /**
     * Create a new {@link ObsEventTrigger}.
     * 
     * @param spec
     *          crontab specification that defines how often to poll
     */
    @DataBoundConstructor
        public ObsEventTrigger(String spec, String obs_event, String amqp_server, String logging) throws ANTLRException {
            super(spec);
            this.obs_event = obs_event;
            this.amqp_server = amqp_server;
            this.logging = logging;
            obMatchEvent = (JSONObject) JSONSerializer.toJSON( obs_event );
            sMatchNames = obMatchEvent.names();
        }

    protected void finalize() throws Throwable
    {
        super.finalize();
        stop();
    } 
    

    public String getObs_event() {
        return obs_event;
    }
    public String getLogging() {
        return logging;
    }
    public JSONObject getGotObsEvent() {
        return obGotEvent;
    }
    public String getAmqp_server() {
        return amqp_server;
    }
    @Override
        public void start(SCMedItem project, boolean newInstance) {
            try {
                ConnectionFactory connFactory = new ConnectionFactory();

                connFactory.setHost(amqp_server);
                connFactory.setPort(AMQP.PROTOCOL.PORT);
                connFactory.setVirtualHost("mailer_vhost");
                connFactory.setUsername("mailer");
                connFactory.setPassword("mailerpwd");

                Connection conn = connFactory.newConnection();
                channel = conn.createChannel();

                obs_queue = project.getName();
                channel.queueDeclare(obs_queue, false, false, false, null);
                //all messages sent to exchange amq.topic with routing key "mailer" will be in obs_queue
                channel.queueBind(obs_queue, "amq.topic", "mailer");

                System.out.println("OBS Event Trigger started("+channel+"+: "+project.getName()+";"+newInstance+"\n");
                super.start(project,newInstance);

                cleaner = new Cleaner(this);
                Runtime.getRuntime().addShutdownHook(cleaner);
            }
            catch (Exception ex) {
                System.err.println("ObsEventTrigger::start caught exception: " + ex);
                ex.printStackTrace();
            }
        }
    public void _stop() {
        try {
            channel.queueDelete(obs_queue);
            System.out.println("OBS Event Trigger stoped("+channel+")\n");
            channel.close();
            super.stop();
        }
        catch (Exception ex) {
            System.err.println("ObsEventTrigger::stop caught exception: " + ex);
            ex.printStackTrace();
        }
    }
    @Override
        public void stop() {
            Runtime.getRuntime().removeShutdownHook(cleaner);
            _stop();
        }

    /**
     * {@inheritDoc}
     */
    @Override
        public void run() {
            try {
                GetResponse response = channel.basicGet(obs_queue, false);
                String logString = new String("");
                int choice = Integer.parseInt(logging);
                while(response != null) {
                    AMQP.BasicProperties props = response.getProps();
                    String gotEvent = new String(response.getBody());
                    long deliveryTag = response.getEnvelope().getDeliveryTag();
                    channel.basicAck(response.getEnvelope().getDeliveryTag(), false);

                    obGotEvent = (JSONObject) JSONSerializer.toJSON( gotEvent );
                    String causeString = obGotEvent.toString(4);

                    System.out.println("OBS Event Trigger got an Event: \n"+causeString);
                    if(choice == 2)
                        logString += causeString+"\n";

                    int i = 0, len = sMatchNames.size();
                    for(i = 0; i < len; i++) {
                        String key = sMatchNames.optString(i);
                        if(!obGotEvent.has(key) || !obGotEvent.getString(key).equals(obMatchEvent.getString(key))) {
                            causeString = "";
                            break;
                        }
                    }
                    if(!causeString.equals("")) {
                        if(choice == 1)
                            logString = causeString;
                        String cause = String.format("Caused by OBS Event: \n%s\n", causeString);
                        job.scheduleBuild(0, new ObsEventCause(cause));
                        break;
                    }
                    else 
                        response = channel.basicGet(obs_queue, false);
                }
                if(!logString.equals("")) {
                    FileWriter logFile = new FileWriter(job.getRootDir()+"/obsevent.txt", (choice==2));
                    BufferedWriter logWriter = new BufferedWriter(logFile);
                    logWriter.write(logString);
                    logWriter.close();
                }
            } catch (Exception ex) {
                System.err.println("ObsEventTrigger::run caught exception: " + ex);
                ex.printStackTrace();
            }
        }

    /**
    */
    @Override
        public String toString() {
            return getClass().getSimpleName() + "{spec:" + spec + "}";
        }

    /**
     * Registers {@link ObsEventTrigger} as a {@link Trigger} extension.
     */
    @Extension
        public static final class DescriptorImpl extends TriggerDescriptor {

            /**
             * {@inheritDoc}
             */
            @Override
                public boolean isApplicable(Item item) {
                    return item instanceof SCMedItem;
                }

            public ListBoxModel doFillLoggingItems() { 
                ListBoxModel model = new ListBoxModel(); 
                model.add("no logging","0");
                model.add("logging the latest matched event","1");
                model.add("logging all received event","2");
                return model; 
            }
            
            /**
             * {@inheritDoc}
             */
            @Override
                public String getDisplayName() {
                    return "OBS Event Trigger";
                }

            /*
               @Override
               public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
               save();
               return super.configure(req,formData);
               }
               */

            public FormValidation doCheckAmqp_server(@QueryParameter final String value) {
                try {
                    ConnectionFactory connFactory = new ConnectionFactory();

                    connFactory.setHost(value);
                    connFactory.setPort(AMQP.PROTOCOL.PORT);
                    connFactory.setVirtualHost("mailer_vhost");
                    connFactory.setUsername("mailer");
                    connFactory.setPassword("mailerpwd");

                    Connection conn = connFactory.newConnection();
                    Channel ch = conn.createChannel();
                    ch.close();
                    conn.close();
                    return FormValidation.ok();
                } catch (Exception e) { 
                    return FormValidation.error("Can not connect to "+value);
                }
            }
            public FormValidation doCheckObs_event(@QueryParameter final String value) {
                if(!value.endsWith(";"))
                    return FormValidation.error("Please end the String with a ;.");
                try {
                    JSONObject ob = (JSONObject) JSONSerializer.toJSON( value );
                    return FormValidation.ok();
                } catch (JSONException e) { 
                    return FormValidation.error("Please input a valid JSON String.");
                }
            }
        }

    private static final class ObsEventCause extends Cause {
        private final String description;

        public ObsEventCause(String description) {
            this.description = description;
        }

        public String getShortDescription() {
            return description;
        }
    }
}
