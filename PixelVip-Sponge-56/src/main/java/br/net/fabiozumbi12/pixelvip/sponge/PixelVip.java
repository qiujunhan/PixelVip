package br.net.fabiozumbi12.pixelvip.sponge;

import br.net.fabiozumbi12.pixelvip.sponge.Packages.PackageManager;
import br.net.fabiozumbi12.pixelvip.sponge.PaymentsAPI.MercadoPagoHook;
import br.net.fabiozumbi12.pixelvip.sponge.PaymentsAPI.PagSeguroHook;
import br.net.fabiozumbi12.pixelvip.sponge.PaymentsAPI.PayPalHook;
import br.net.fabiozumbi12.pixelvip.sponge.PaymentsAPI.PaymentModel;
import br.net.fabiozumbi12.pixelvip.sponge.cmds.PVCommands;
import br.net.fabiozumbi12.pixelvip.sponge.config.PVConfig;
import com.google.inject.Inject;
import ninja.leaping.configurate.objectmapping.GuiceObjectMapperFactory;
import org.slf4j.Logger;
import org.spongepowered.api.Platform;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.args.GenericArguments;
import org.spongepowered.api.command.spec.CommandSpec;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.config.DefaultConfig;
import org.spongepowered.api.entity.living.player.User;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.game.GameReloadEvent;
import org.spongepowered.api.event.game.state.GameStartedServerEvent;
import org.spongepowered.api.event.game.state.GameStoppingServerEvent;
import org.spongepowered.api.event.network.ClientConnectionEvent;
import org.spongepowered.api.plugin.Dependency;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.text.Text;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.spongepowered.api.Sponge.*;

@Plugin(id = "pixelvip",
        name = "PixelVip",
        version = VersionData.VERSION,
        authors = "FabioZumbi12",
        description = "Plugin to give VIP to your players.",
        dependencies = {
                @Dependency(id = "PagSeguroAPI", optional = true),
                @Dependency(id = "MercadoPagoAPI", optional = true),
                @Dependency(id = "PayPalAPI", optional = true)})
public class PixelVip {
    private static PixelVip plugin;
    @Inject
    public GuiceObjectMapperFactory factory;
    public HashMap<String, String> processTrans;
    PVPermsAPI perms;
    @Inject
    private Logger logger;
    @Inject
    @ConfigDir(sharedRoot = false)
    private Path configDir;
    @Inject
    @DefaultConfig(sharedRoot = false)
    private File defConfig;
    private PVConfig config;
    private PVUtil util;
    private PVCommands cmds;
    private Task task;
    private List<PaymentModel> payments;
    private PackageManager packageManager;

    public static PixelVip get() {
        return plugin;
    }

    public Logger getLogger() {
        return logger;
    }

    public File configDir() {
        return this.configDir.toFile();
    }

    public PVConfig getConfig() {
        return config;
    }

    public PVPermsAPI getPerms() {
        return this.perms;
    }

    public PVUtil getUtil() {
        return this.util;
    }

    public PVCommands getCmds() {
        return this.cmds;
    }

    public List<PaymentModel> getPayments() {
        return this.payments;
    }

    public PackageManager getPackageManager() {
        return this.packageManager;
    }

    @Listener
    public void onServerStart(GameStartedServerEvent event) {
        try {
            plugin = this;
            logger.info("Init utils module...");
            this.util = new PVUtil(this);

            logger.info("Init config module...");
            this.config = new PVConfig(factory);

            logger.info("Init perms module...");
            this.setCompatperms();

            logger.info("Init commands module...");
            this.cmds = new PVCommands(this);
            CommandSpec spongevip = CommandSpec.builder()
                    .description(Text.of("Use to see the plugin info and reload."))
                    .permission("pixelvip.cmd.reload")
                    .arguments(GenericArguments.optional(GenericArguments.choices(Text.of("reload"),
                            new HashMap<String, String>(){{
                                put("reload","reload");
                    }})))
                    .executor((src, args) -> {
                        {
                            if (args.hasAny("reload")) {
                                this.config = new PVConfig(factory);
                                this.cmds.reload();
                                reloadVipTask();
                                src.sendMessage(util.toText("&aPixelVip reloaded"));
                            } else {
                                src.sendMessage(util.toText("&a> PixelVip by &6FabioZumbi12"));
                            }
                            return CommandResult.success();
                        }
                    })
                    .build();
            Sponge.getCommandManager().register(this, spongevip, "pixelvip");

            logger.info("Init scheduler module...");
            reloadVipTask();

            //payment apis
            setupPayments();

            //package manager
            packageManager = new PackageManager(this, factory);

            logger.info(util.toColor("We have &6" + config.getVipList().size() + " &ractive Vips"));
            logger.info(util.toColor("&aPixelVip enabled!&r"));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setupPayments() {
        payments = new ArrayList<>();
        //pagseguro
        if (getConfig().root().apis.pagseguro.use && getPluginManager().getPlugin("PagSeguroAPI").isPresent()) {
            this.payments.add(new PagSeguroHook(this));
            logger.info("-> PagSeguroAPI found and hooked.");
        }

        //mercadopago
        if (getConfig().root().apis.mercadopago.use && getPluginManager().getPlugin("MercadoPagoAPI").isPresent()) {
            this.payments.add(new MercadoPagoHook(this));
            logger.info("-> MercadoPagoAPI found and hooked.");
        }

        //paypal
        if (getConfig().root().apis.paypal.use && getPluginManager().getPlugin("PayPalAPI").isPresent()) {
            this.payments.add(new PayPalHook(this));
            logger.info("-> PayPalAPI found and hooked.");
        }
    }

    private void setCompatperms() {
        //init perms
        try {
            String v = getPlatform().getContainer(Platform.Component.API).getVersion().get();
            logger.info("Sponge version " + v);

            if (v.startsWith("5") || v.startsWith("6")) {
                this.perms = (PVPermsAPI) Class.forName("br.net.fabiozumbi12.pixelvip.sponge.PVPermsAPI56").newInstance();
            }
            if (v.startsWith("7") || v.startsWith("8")) {
                this.perms = (PVPermsAPI) Class.forName("br.net.fabiozumbi12.pixelvip.sponge.PVPermsAPI78").newInstance();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void reloadCmd() {
        logger.info("Reloading config module...");
        config.reloadVips();

        //package manager
        packageManager = new PackageManager(this, factory);

        reloadVipTask();

        logger.info(util.toColor("We have " + config.getVipList().size() + " active Vips"));
        logger.info(util.toColor("PixelVip reloaded"));
    }

    @Listener
    public void onStopServer(GameStoppingServerEvent e) {
        task.cancel();
        getConfig().saveConfigAll();
        logger.info(util.toColor("&aPixelVip disabled!&r"));
    }

    @Listener
    public void onReloadPlugins(GameReloadEvent event) {
        this.config = new PVConfig(factory);
        this.cmds.reload();
        reloadVipTask();
        logger.info(util.toColor("&aPixelVip reloaded"));
    }

    @Listener
    public void onPlayerJoin(ClientConnectionEvent.Login e) {
        User p = e.getTargetUser();

        //check player uuid async
        Sponge.getScheduler().createAsyncExecutor(this).schedule(() ->
                getConfig().getVipList().forEach((key, value) -> {
                    for (String[] vipInfo : value) {
                        String oldUUid = getConfig().getVipUUID(p.getName());
                        if (vipInfo[4].equals(p.getName()) && !p.getUniqueId().toString().equals(oldUUid)) {
                            getConfig().changeUUIDs(oldUUid, p.getUniqueId().toString());
                        }
                    }
                }), 0, TimeUnit.SECONDS);

        if (getConfig().queueCmds()) {
            getScheduler().createSyncExecutor(this).schedule(new Runnable() {
                @Override
                public void run() {
                    List<String> qcmds = getConfig().getQueueCmds(p.getUniqueId().toString());
                    qcmds.forEach((cmd) -> getScheduler().createSyncExecutor(this).schedule(() -> {
                        Sponge.getCommandManager().process(Sponge.getServer().getConsole(), cmd);
                    }, 500, TimeUnit.MILLISECONDS));
                }
            }, 3, TimeUnit.SECONDS);
        }
    }

    private void reloadVipTask() {
        logger.info("Reloading tasks...");
        if (task != null) {
            task.cancel();
            logger.info("-> Task stoped");
        }

        task = getScheduler().createTaskBuilder().interval(60, TimeUnit.SECONDS).execute(t -> {
            getConfig().getVipList().forEach((uuid, value) -> {
                Optional<User> p = util.getUser(UUID.fromString(uuid));
                getConfig().getVipList().get(uuid).forEach((vipInfo) -> {
                    long dur = new Long(vipInfo[0]);
                    if (p.isPresent()) {
                        User user = p.get();
                        if (!perms.getPlayerGroups(user).contains(vipInfo[1])) {
                            config.runChangeVipCmds(uuid, vipInfo[1], perms.getHighestGroup(p.get()));
                        }
                        if (dur <= util.getNowMillis()) {
                            getConfig().removeVip(p.get().getUniqueId().toString(), Optional.of(vipInfo[1]));
                            if (p.get().isOnline()) {
                                p.get().getPlayer().get().sendMessage(util.toText(config.getLang("_pluginTag", "vipEnded").replace("{vip}", vipInfo[1])));
                            }
                            logger.info(util.toColor(config.getLang("_pluginTag") + "&bThe vip &6" + vipInfo[1] + "&b of player &6" + p.get().getName() + " &bhas ended!"));
                        }
                    }
                });
            });
        }).async().submit(this);
        logger.info("-> Task started");
    }

    public void addLog(String log) {
        String timeStamp = new SimpleDateFormat("dd.MM.yyyy - HH:mm:ss").format(Calendar.getInstance().getTime());
        try {
            File folder = new File(this.configDir + File.separator + "logs");
            if (!folder.exists()) {
                folder.mkdir();
            }
            File logs = new File(folder + File.separator + "logs.log");

            FileWriter fw = new FileWriter(logs, true);
            fw.append(timeStamp + " - PixelVip Log: " + log);
            fw.append("\n");
            fw.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}