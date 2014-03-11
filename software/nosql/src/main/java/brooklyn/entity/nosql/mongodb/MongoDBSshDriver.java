package brooklyn.entity.nosql.mongodb;

import static com.google.common.base.Preconditions.checkState;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.basic.AbstractSoftwareProcessSshDriver;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.lifecycle.ScriptHelper;
import brooklyn.entity.drivers.downloads.DownloadResolver;
import brooklyn.location.OsDetails;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.util.net.Networking;
import brooklyn.util.ssh.BashCommands;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

public class MongoDBSshDriver extends AbstractSoftwareProcessSshDriver implements MongoDBDriver {

    private static final Logger LOG = LoggerFactory.getLogger(MongoDBSshDriver.class);

    public MongoDBSshDriver(MongoDBServerImpl entity, SshMachineLocation machine) {
        super(entity, machine);
    }

    @Override
    public MongoDBServerImpl getEntity() {
        return MongoDBServerImpl.class.cast(super.getEntity());
    }

    public String getDataDirectory() {
        String result = entity.getConfig(MongoDBServer.DATA_DIRECTORY);
        if (result!=null) return result;
        return getRunDir() + "/data";
    }

    @Override
    public void install() {
        DownloadResolver resolver = Entities.newDownloader(this);
        List<String> urls = resolver.getTargets();
        String saveAs = resolver.getFilename();
        setExpandedInstallDir(getInstallDir()+"/"+resolver.getUnpackedDirectoryName(getBaseName()));

        List<String> commands = new LinkedList<String>();
        commands.addAll(BashCommands.commandsToDownloadUrlsAs(urls, saveAs));
        commands.add(BashCommands.INSTALL_TAR);
        commands.add("tar xzfv " + saveAs);

        newScript(INSTALLING)
                .failOnNonZeroResultCode()
                .body.append(commands).execute();
    }

    @Override
    public void customize() {
        Map<?,?> ports = ImmutableMap.of("port", getServerPort());
        Networking.checkPortsValid(ports);
        String command = String.format("mkdir -p %s", getDataDirectory());
        newScript(CUSTOMIZING)
                .updateTaskAndFailOnNonZeroResultCode()
                .body.append(command).execute();

        String templateUrl = entity.getConfig(MongoDBServer.MONGODB_CONF_TEMPLATE_URL);
        if (!Strings.isNullOrEmpty(templateUrl)) copyTemplate(templateUrl, getConfFile());
    }

    @Override
    public void launch() {
        MongoDBServer server = getEntity();
        Integer port = server.getAttribute(MongoDBServer.PORT);

        ImmutableList.Builder<String> argsBuilder = ImmutableList.<String>builder()
                .add("--config", getConfFile())
                .add("--pidfilepath", getPidFile())
                .add("--dbpath", getDataDirectory())
                .add("--logpath", getLogFile())
                .add("--port", port.toString())
                .add("--fork");

        if (server.isReplicaSetMember()) {
            String replicaSetName = server.getReplicaSet().getName();
            checkState(!Strings.isNullOrEmpty(replicaSetName), "Replica set name must not be null or empty");
            argsBuilder.add("--replSet", replicaSetName);
        }

        if (Boolean.TRUE.equals(server.getConfig(MongoDBServer.ENABLE_REST_INTERFACE)))
            argsBuilder.add("--rest");

        String args = Joiner.on(" ").join(argsBuilder.build());
        String command = String.format("%s/bin/mongod %s > out.log 2> err.log < /dev/null", getExpandedInstallDir(), args);
        LOG.info(command);
        newScript(LAUNCHING)
                .updateTaskAndFailOnNonZeroResultCode()
                .body.append(command).execute();

    }

    /**
     * Kills the server with SIGINT. Sending SIGKILL is likely to resuult in data corruption.
     * @see <a href="http://docs.mongodb.org/manual/tutorial/manage-mongodb-processes/#sending-a-unix-int-or-term-signal">http://docs.mongodb.org/manual/tutorial/manage-mongodb-processes/#sending-a-unix-int-or-term-signal</a>
     */
    @Override
    public void stop() {
        // We could also use SIGTERM (15)
        new ScriptHelper(this, "Send SIGINT to MongoDB server")
                .body.append("kill -2 $(cat " + getPidFile() + ")")
                .execute();
    }

    @Override
    public boolean isRunning() {
        Map<String, ?> flags = ImmutableMap.of("usePidFile", getPidFile());
        return newScript(flags, CHECK_RUNNING).execute() == 0;
    }

    protected String getBaseName() {
        return getOsTag() + "-" + entity.getConfig(MongoDBServer.SUGGESTED_VERSION);
    }

    // IDE note: This is used by MongoDBServer.DOWNLOAD_URL
    public String getOsDir() {
        return (getLocation().getOsDetails().isMac()) ? "osx" : "linux";
    }

    public String getOsTag() {
        OsDetails os = getLocation().getOsDetails();
        if (os == null) {
            // Default to generic linux
            return "mongodb-linux-x86_64";
        } else if (os.isMac()) {
            // Mac is 64bit only
            return "mongodb-osx-x86_64";
        } else {
            String arch = os.is64bit() ? "x86_64" : "i686";
            return "mongodb-linux-" + arch;
        }
    }

    protected String getLogFile() {
        return getRunDir() + "/log.txt";
    }

    protected String getPidFile() {
        return getRunDir() + "/pid";
    }

    protected Integer getServerPort() {
        return entity.getAttribute(MongoDBServer.PORT);
    }

    private String getConfFile() {
        return getRunDir() + "/mongo.conf";
    }
}
