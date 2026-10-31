package dev.mineconflux;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.link.PlayerLink;
import org.geysermc.floodgate.api.player.FloodgatePlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class MineConfluxMod implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("mineconflux");
    private static final Gson GSON = new Gson();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long RATE_WINDOW_MS = TimeUnit.MINUTES.toMillis(10);
    private static final UUID DEMO_JAVA_UUID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID DEMO_BEDROCK_UUID = UUID.fromString("00000000-0000-0000-0009-999999999999");

    private final Map<String, Identity> codes = new ConcurrentHashMap<>();
    private final Map<String, LinkSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, AttemptWindow> attempts = new ConcurrentHashMap<>();
    private final Set<UUID> lockedPlayers = ConcurrentHashMap.newKeySet();
    private PortalConfig config;
    private MinecraftServer server;
    private HttpServer httpServer;

    @Override
    public void onInitialize() {
        config = loadConfig();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("linkcode").executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                    issueCode(player);
                    return 1;
                })));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, minecraftServer) -> {
            UUID id = handler.player.getUuid();
            if (lockedPlayers.contains(id)) {
                handler.disconnect(Text.literal("账号数据正在迁移，请稍后重新连接。"));
            }
        });

        ServerLifecycleEvents.SERVER_STARTED.register(minecraftServer -> {
            server = minecraftServer;
            if (config.enableDemoCodes) seedDemoCodes();
            startHttpServer();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(minecraftServer -> stopHttpServer());
    }

    private void issueCode(ServerPlayerEntity player) {
        purgeExpired();
        FloodgateApi api = FloodgateApi.getInstance();
        FloodgatePlayer floodgate = api == null ? null : api.getPlayer(player.getUuid());
        boolean bedrock = floodgate != null;
        if (bedrock && floodgate.isLinked()) {
            player.sendMessage(Text.literal("该基岩账号已经绑定 Java 账号，无需再次绑定。"), false);
            return;
        }

        UUID uuid = bedrock ? floodgate.getJavaUniqueId() : player.getUuid();
        String displayName = bedrock ? floodgate.getUsername() : player.getGameProfile().getName();
        String serverName = bedrock ? floodgate.getJavaUsername() : player.getGameProfile().getName();
        String xuid = bedrock ? floodgate.getXuid() : "";

        codes.entrySet().removeIf(entry -> entry.getValue().uuid().equals(uuid));
        String code;
        do {
            code = String.format(Locale.ROOT, "%08d", RANDOM.nextInt(100_000_000));
        } while (codes.containsKey(code));

        server.getPlayerManager().saveAllPlayerData();
        codes.put(code, new Identity(uuid, displayName, serverName, bedrock, xuid,
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(config.codeLifetimeMinutes)));
        player.sendMessage(Text.literal("§a账号中心验证码：§f" + code + "§7（"
                + config.codeLifetimeMinutes + " 分钟有效，仅可使用一次）"), false);
        player.sendMessage(Text.literal("§7请在网页提交 Java 与基岩两个验证码，然后让两个角色退出服务器。"), false);
    }

    private void startHttpServer() {
        try {
            httpServer = HttpServer.create(new InetSocketAddress(config.bindAddress, config.port), 0);
            httpServer.createContext("/", this::handleIndex);
            httpServer.createContext("/api/verify", this::handleVerify);
            httpServer.createContext("/api/commit", this::handleCommit);
            httpServer.setExecutor(Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "mineconflux-http");
                thread.setDaemon(true);
                return thread;
            }));
            httpServer.start();
            LOGGER.info("MineConflux portal listening on http://{}:{}", config.bindAddress, config.port);
        } catch (IOException exception) {
            LOGGER.error("Could not start MineConflux portal", exception);
        }
    }

    private void stopHttpServer() {
        if (httpServer != null) {
            httpServer.stop(1);
            httpServer = null;
        }
    }

    private void handleIndex(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod()) || !"/".equals(exchange.getRequestURI().getPath())) {
            respondText(exchange, 404, "Not found", "text/plain; charset=utf-8");
            return;
        }
        try (InputStream stream = MineConfluxMod.class.getResourceAsStream("/web/index.html")) {
            if (stream == null) {
                respondText(exchange, 500, "Portal resource missing", "text/plain; charset=utf-8");
                return;
            }
            respondBytes(exchange, 200, stream.readAllBytes(), "text/html; charset=utf-8");
        }
    }

    private void handleVerify(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) return;
        String remoteAddress = exchange.getRemoteAddress().getAddress().getHostAddress();
        try {
            checkRateLimit(remoteAddress);
            purgeExpired();
            JsonObject request = readJson(exchange);
            String code1 = getString(request, "code1");
            String code2 = getString(request, "code2");
            Identity first = codes.get(code1);
            Identity second = codes.get(code2);
            if (first == null || second == null || code1.equals(code2)) {
                throw new PortalException("验证码无效、已使用或已过期。请在游戏内重新输入 /linkcode。", 400);
            }
            Identity java = first.bedrock() ? second : first;
            Identity bedrock = first.bedrock() ? first : second;
            if (java.bedrock() || !bedrock.bedrock()) {
                throw new PortalException("必须提交一个 Java 验证码和一个基岩验证码。", 400);
            }

            String token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
            LinkSession session = new LinkSession(token, java, bedrock,
                    System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(config.sessionLifetimeMinutes));
            sessions.put(token, session);
            attempts.remove(remoteAddress);
            boolean demo = isDemo(java) || isDemo(bedrock);
            if (!demo) {
                codes.remove(code1);
                codes.remove(code2);
            }

            JsonObject result = new JsonObject();
            result.addProperty("token", token);
            result.addProperty("demo", demo);
            JsonArray profiles = new JsonArray();
            profiles.add(readProfile(java));
            profiles.add(readProfile(bedrock));
            result.add("profiles", profiles);
            respondJson(exchange, 200, result);
        } catch (PortalException exception) {
            recordFailure(remoteAddress);
            respondError(exchange, exception.status, exception.getMessage());
        } catch (Exception exception) {
            LOGGER.error("Verify request failed", exception);
            respondError(exchange, 500, "读取存档失败，请联系管理员查看服务器日志。");
        }
    }

    private void handleCommit(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) return;
        try {
            purgeExpired();
            JsonObject request = readJson(exchange);
            String token = getString(request, "token");
            String choice = getString(request, "choice");
            LinkSession session = sessions.get(token);
            if (session == null || session.completed) {
                throw new PortalException("操作会话无效或已过期，请重新取得验证码。", 400);
            }
            if (!choice.equals("java") && !choice.equals("bedrock")) {
                throw new PortalException("无效的进度选择。", 400);
            }
            if (isDemo(session.javaIdentity) || isDemo(session.bedrockIdentity)) {
                throw new PortalException("演示验证码只能预览界面，不能执行真实迁移或绑定。", 403);
            }
            if (isOnline(session.javaIdentity.uuid()) || isOnline(session.bedrockIdentity.uuid())) {
                throw new PortalException("两个角色必须都退出服务器后才能迁移。", 409);
            }
            if (!session.tryStart()) {
                throw new PortalException("该操作正在处理或已经完成，请勿重复提交。", 409);
            }

            lockedPlayers.add(session.javaIdentity.uuid());
            lockedPlayers.add(session.bedrockIdentity.uuid());
            Path backup = null;
            try {
                if (isOnline(session.javaIdentity.uuid()) || isOnline(session.bedrockIdentity.uuid())) {
                    throw new PortalException("角色在提交期间重新上线，迁移已取消。", 409);
                }
                backup = createBackup(session);
                if (choice.equals("bedrock")) {
                    migrateFiles(session.bedrockIdentity.uuid(), session.javaIdentity.uuid());
                }
                linkAccounts(session);
                if (choice.equals("bedrock")) {
                    try {
                        migrateScores(session.bedrockIdentity.serverName(), session.javaIdentity.serverName());
                    } catch (Exception scoreException) {
                        LOGGER.error("Account link succeeded but scoreboard migration failed", scoreException);
                    }
                }
                session.markCompleted();
                JsonObject result = new JsonObject();
                result.addProperty("message", "绑定完成。以后 Java 和手机端都会以 "
                        + session.javaIdentity.name() + " 登录并使用同一份数据。\n备份编号：" + backup.getFileName());
                respondJson(exchange, 200, result);
            } catch (Exception exception) {
                if (choice.equals("bedrock") && backup != null) {
                    try {
                        restoreJavaFromBackup(backup, session.javaIdentity.uuid());
                    } catch (Exception restoreException) {
                        exception.addSuppressed(restoreException);
                    }
                }
                throw exception;
            } finally {
                lockedPlayers.remove(session.javaIdentity.uuid());
                lockedPlayers.remove(session.bedrockIdentity.uuid());
                session.finish();
            }
        } catch (PortalException exception) {
            respondError(exchange, exception.status, exception.getMessage());
        } catch (Exception exception) {
            LOGGER.error("Commit request failed", exception);
            respondError(exchange, 500, "绑定失败，未确认的更改不会生效；管理员可从备份恢复。请查看服务器日志。");
        }
    }

    private JsonObject readProfile(Identity identity) throws IOException {
        if (isDemo(identity)) return createDemoProfile(identity);
        Path playerFile = playerDataDirectory().resolve(identity.uuid() + ".dat");
        if (!Files.exists(playerFile)) {
            throw new PortalException("找不到 " + identity.name() + " 的玩家存档；请先进入本服务器一次。", 404);
        }
        NbtCompound root = NbtIo.readCompressed(playerFile, NbtSizeTracker.ofUnlimitedBytes());
        JsonObject profile = new JsonObject();
        profile.addProperty("uuid", identity.uuid().toString());
        profile.addProperty("name", identity.name());
        profile.addProperty("bedrock", identity.bedrock());
        profile.addProperty("online", isOnline(identity.uuid()));
        profile.addProperty("dimension", root.getString("Dimension"));
        profile.addProperty("position", formatPosition(root.getList("Pos", NbtElement.DOUBLE_TYPE)));
        profile.addProperty("level", root.getInt("XpLevel"));
        profile.addProperty("totalXp", root.getInt("XpTotal"));
        profile.addProperty("health", roundOne(root.getFloat("Health")));
        profile.addProperty("food", root.getInt("foodLevel"));
        profile.add("inventoryItems", itemsToJson(root.getList("Inventory", NbtElement.COMPOUND_TYPE)));
        profile.add("enderItems", itemsToJson(root.getList("EnderItems", NbtElement.COMPOUND_TYPE)));
        profile.addProperty("advancements", countAdvancements(identity.uuid()));
        profile.addProperty("playTime", readPlayTime(identity.uuid()));
        profile.addProperty("scores", readScores(identity.serverName()));
        profile.addProperty("lastSaved", formatFileTime(Files.getLastModifiedTime(playerFile)));
        return profile;
    }

    private String formatPosition(NbtList position) {
        if (position.size() < 3) return "未知";
        return String.format(Locale.ROOT, "%.1f, %.1f, %.1f",
                position.getDouble(0), position.getDouble(1), position.getDouble(2));
    }

    private JsonArray itemsToJson(NbtList items) {
        JsonArray result = new JsonArray();
        for (NbtElement element : items) {
            if (!(element instanceof NbtCompound item)) continue;
            String id = item.getString("id");
            int count = item.contains("count", NbtElement.NUMBER_TYPE) ? item.getInt("count") : item.getByte("Count");
            String nbt = item.toString();
            JsonObject json = new JsonObject();
            json.addProperty("slot", item.getByte("Slot"));
            json.addProperty("id", id);
            json.addProperty("count", Math.max(1, count));
            json.addProperty("enchanted", nbt.contains("enchantments") || nbt.contains("stored_enchantments"));
            json.addProperty("nbt", nbt.length() > 1200 ? nbt.substring(0, 1200) + "…" : nbt);
            result.add(json);
        }
        return result;
    }

    private int countAdvancements(UUID uuid) {
        Path file = worldDirectory().resolve("advancements").resolve(uuid + ".json");
        if (!Files.exists(file)) return 0;
        try {
            JsonObject root = GSON.fromJson(Files.readString(file), JsonObject.class);
            int count = 0;
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (entry.getValue().isJsonObject()
                        && entry.getValue().getAsJsonObject().has("done")
                        && entry.getValue().getAsJsonObject().get("done").getAsBoolean()) count++;
            }
            return count;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String readPlayTime(UUID uuid) {
        Path file = worldDirectory().resolve("stats").resolve(uuid + ".json");
        if (!Files.exists(file)) return "0 小时";
        try {
            JsonObject root = GSON.fromJson(Files.readString(file), JsonObject.class);
            JsonObject custom = root.getAsJsonObject("stats").getAsJsonObject("minecraft:custom");
            long ticks = custom.has("minecraft:play_time") ? custom.get("minecraft:play_time").getAsLong()
                    : custom.has("minecraft:play_one_minute") ? custom.get("minecraft:play_one_minute").getAsLong() : 0;
            return String.format(Locale.ROOT, "%.1f 小时", ticks / 72000.0);
        } catch (Exception ignored) {
            return "未知";
        }
    }

    private String readScores(String playerName) {
        Path file = worldDirectory().resolve("data").resolve("scoreboard.dat");
        if (!Files.exists(file)) return "无";
        try {
            NbtCompound root = NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes());
            NbtCompound data = root.contains("data", NbtElement.COMPOUND_TYPE)
                    ? root.getCompound("data") : root.getCompound("Data");
            NbtList scores = data.getList("PlayerScores", NbtElement.COMPOUND_TYPE);
            List<String> found = new ArrayList<>();
            for (NbtElement element : scores) {
                if (element instanceof NbtCompound score && playerName.equals(score.getString("Name"))) {
                    found.add(score.getString("Objective") + "=" + score.getInt("Score"));
                }
            }
            return found.isEmpty() ? "无" : String.join("、", found);
        } catch (Exception ignored) {
            return "读取失败";
        }
    }

    private Path createBackup(LinkSession session) throws IOException {
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneId.of(config.timeZone)).format(Instant.now());
        Path backup = worldDirectory().getParent().resolve("mineconflux").resolve("backups")
                .resolve(stamp + "-" + safeName(session.javaIdentity.name()) + "-" + safeName(session.bedrockIdentity.name()));
        Files.createDirectories(backup);
        backupIdentity(session.javaIdentity.uuid(), "java", backup);
        backupIdentity(session.bedrockIdentity.uuid(), "bedrock", backup);
        copyIfExists(worldDirectory().resolve("data").resolve("scoreboard.dat"), backup.resolve("scoreboard.dat"));
        JsonObject metadata = new JsonObject();
        metadata.addProperty("javaUuid", session.javaIdentity.uuid().toString());
        metadata.addProperty("javaName", session.javaIdentity.name());
        metadata.addProperty("bedrockUuid", session.bedrockIdentity.uuid().toString());
        metadata.addProperty("bedrockName", session.bedrockIdentity.name());
        metadata.addProperty("createdAt", Instant.now().toString());
        Files.writeString(backup.resolve("metadata.json"), GSON.toJson(metadata), StandardCharsets.UTF_8);
        return backup;
    }

    private void backupIdentity(UUID uuid, String prefix, Path backup) throws IOException {
        copyIfExists(playerDataDirectory().resolve(uuid + ".dat"), backup.resolve(prefix + "-playerdata.dat"));
        copyIfExists(worldDirectory().resolve("advancements").resolve(uuid + ".json"), backup.resolve(prefix + "-advancements.json"));
        copyIfExists(worldDirectory().resolve("stats").resolve(uuid + ".json"), backup.resolve(prefix + "-stats.json"));
    }

    private void restoreJavaFromBackup(Path backup, UUID javaUuid) throws IOException {
        restoreOrRemove(backup.resolve("java-playerdata.dat"), playerDataDirectory().resolve(javaUuid + ".dat"));
        restoreOrRemove(backup.resolve("java-advancements.json"),
                worldDirectory().resolve("advancements").resolve(javaUuid + ".json"));
        restoreOrRemove(backup.resolve("java-stats.json"),
                worldDirectory().resolve("stats").resolve(javaUuid + ".json"));
    }

    private static void restoreOrRemove(Path backup, Path target) throws IOException {
        if (Files.exists(backup)) atomicCopy(backup, target);
        else Files.deleteIfExists(target);
    }

    private void migrateFiles(UUID source, UUID target) throws IOException {
        atomicCopy(playerDataDirectory().resolve(source + ".dat"), playerDataDirectory().resolve(target + ".dat"));
        copyOptionalAtomic(worldDirectory().resolve("advancements").resolve(source + ".json"),
                worldDirectory().resolve("advancements").resolve(target + ".json"));
        copyOptionalAtomic(worldDirectory().resolve("stats").resolve(source + ".json"),
                worldDirectory().resolve("stats").resolve(target + ".json"));
    }

    private void migrateScores(String sourceName, String targetName) throws Exception {
        Set<String> objectives = scoreboardObjectivesFor(sourceName);
        if (objectives.isEmpty()) return;
        server.submit(() -> {
            for (String objective : objectives) {
                String command = "scoreboard players operation " + quote(targetName) + " " + quote(objective)
                        + " = " + quote(sourceName) + " " + quote(objective);
                server.getCommandManager().executeWithPrefix(server.getCommandSource().withLevel(4), command);
            }
            return null;
        }).get(10, TimeUnit.SECONDS);
    }

    private Set<String> scoreboardObjectivesFor(String playerName) {
        Set<String> objectives = new HashSet<>();
        Path file = worldDirectory().resolve("data").resolve("scoreboard.dat");
        if (!Files.exists(file)) return objectives;
        try {
            NbtCompound root = NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes());
            NbtCompound data = root.contains("data", NbtElement.COMPOUND_TYPE)
                    ? root.getCompound("data") : root.getCompound("Data");
            for (NbtElement element : data.getList("PlayerScores", NbtElement.COMPOUND_TYPE)) {
                if (element instanceof NbtCompound score && playerName.equals(score.getString("Name"))) {
                    objectives.add(score.getString("Objective"));
                }
            }
        } catch (Exception exception) {
            LOGGER.warn("Could not inspect scoreboard before migration", exception);
        }
        return objectives;
    }

    private void linkAccounts(LinkSession session) throws Exception {
        FloodgateApi api = FloodgateApi.getInstance();
        if (api == null) throw new PortalException("Floodgate API 不可用。", 500);
        PlayerLink link = api.getPlayerLink();
        if (link == null || !link.isEnabled()) {
            throw new PortalException("本地绑定数据库未启用，请联系管理员。", 503);
        }
        link.linkPlayer(session.bedrockIdentity.uuid(), session.javaIdentity.uuid(), session.javaIdentity.name())
                .get(15, TimeUnit.SECONDS);
    }

    private boolean isOnline(UUID uuid) {
        return server.getPlayerManager().getPlayer(uuid) != null;
    }

    private Path worldDirectory() {
        return playerDataDirectory().getParent();
    }

    private Path playerDataDirectory() {
        return server.getSavePath(WorldSavePath.PLAYERDATA);
    }

    private PortalConfig loadConfig() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("mineconflux.json");
        PortalConfig loaded = new PortalConfig();
        try {
            if (Files.exists(file)) {
                PortalConfig parsed = GSON.fromJson(Files.readString(file), PortalConfig.class);
                if (parsed != null) loaded = parsed;
            }
            loaded.validate();
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(loaded) + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not load " + file, exception);
        }
        return loaded;
    }

    private void seedDemoCodes() {
        try {
            codes.entrySet().removeIf(entry -> isDemo(entry.getValue()));
            String javaCode = newCode();
            String bedrockCode = newCode();
            long expires = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(7);
            codes.put(javaCode, new Identity(DEMO_JAVA_UUID, "DemoJava", "DemoJava", false, "", expires));
            codes.put(bedrockCode, new Identity(DEMO_BEDROCK_UUID, "DemoBedrock", ".DemoBedrock", true, "999999999999", expires));
            Path directory = worldDirectory().getParent().resolve("mineconflux");
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("demo-codes.txt"),
                    "Java=" + javaCode + System.lineSeparator() + "Bedrock=" + bedrockCode + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            LOGGER.info("Read-only portal demo codes generated at {}", directory.resolve("demo-codes.txt"));
        } catch (Exception exception) {
            LOGGER.error("Could not generate portal demo codes", exception);
        }
    }

    private String newCode() {
        String code;
        do {
            code = String.format(Locale.ROOT, "%08d", RANDOM.nextInt(100_000_000));
        } while (codes.containsKey(code));
        return code;
    }

    private static boolean isDemo(Identity identity) {
        return identity.uuid().equals(DEMO_JAVA_UUID) || identity.uuid().equals(DEMO_BEDROCK_UUID);
    }

    private JsonObject createDemoProfile(Identity identity) {
        boolean bedrock = identity.bedrock();
        JsonObject profile = new JsonObject();
        profile.addProperty("uuid", identity.uuid().toString());
        profile.addProperty("name", identity.name());
        profile.addProperty("bedrock", bedrock);
        profile.addProperty("demo", true);
        profile.addProperty("online", false);
        profile.addProperty("dimension", bedrock ? "minecraft:the_nether" : "minecraft:overworld");
        profile.addProperty("position", bedrock ? "128.5, 67.0, -42.5" : "-63.7, 93.0, 85.7");
        profile.addProperty("level", bedrock ? 27 : 41);
        profile.addProperty("totalXp", bedrock ? 912 : 2847);
        profile.addProperty("health", bedrock ? "20.0" : "17.5");
        profile.addProperty("food", bedrock ? 20 : 16);
        profile.addProperty("advancements", bedrock ? 84 : 169);
        profile.addProperty("playTime", bedrock ? "18.6 小时" : "126.4 小时");
        profile.addProperty("scores", bedrock ? "deaths=2、mined=1845" : "deaths=7、mined=12734、money=860");

        JsonArray inventory = new JsonArray();
        if (!bedrock) {
            inventory.add(demoItem(0, "minecraft:netherite_sword", 1, true, "锋利 V；耐久 III；经验修补；Damage: 37"));
            inventory.add(demoItem(1, "minecraft:diamond_pickaxe", 1, true, "效率 V；精准采集；耐久 III；Damage: 482"));
            inventory.add(demoItem(2, "minecraft:bow", 1, true, "力量 V；无限；火矢；Damage: 91"));
            inventory.add(demoItem(3, "minecraft:golden_carrot", 64, false, "标准堆叠物品"));
            inventory.add(demoItem(4, "minecraft:firework_rocket", 32, false, "Flight: 3；含自定义烟花爆炸数据"));
            inventory.add(demoItem(5, "minecraft:filled_map", 1, false, "map_id: 12；锁定地图"));
            inventory.add(demoItem(6, "minecraft:potion", 3, false, "minecraft:long_strength；自定义药水颜色"));
            inventory.add(demoItem(7, "minecraft:enchanted_book", 1, true, "储存附魔：经验修补 I"));
            inventory.add(demoItem(8, "minecraft:written_book", 1, true, "书名：服务器手册；作者：Admin；含 5 页文本"));
            inventory.add(demoItem(9, "minecraft:red_shulker_box", 1, false, "容器 NBT：钻石 ×32、信标 ×1、下界合金锭 ×4"));
            inventory.add(demoItem(10, "minecraft:player_head", 1, false, "profile：自定义玩家头颅纹理"));
            inventory.add(demoItem(11, "minecraft:tipped_arrow", 16, false, "minecraft:strong_harming"));
            inventory.add(demoItem(12, "minecraft:bundle", 1, false, "BundleContents：多种物品"));
            inventory.add(demoItem(13, "minecraft:diamond", 37, false, "普通堆叠物品"));
            inventory.add(demoItem(103, "minecraft:netherite_helmet", 1, true, "保护 IV；水下呼吸 III；耐久 III"));
            inventory.add(demoItem(102, "minecraft:elytra", 1, true, "耐久 III；经验修补；Damage: 101"));
            inventory.add(demoItem(101, "minecraft:netherite_leggings", 1, true, "保护 IV；迅捷潜行 III"));
            inventory.add(demoItem(100, "minecraft:netherite_boots", 1, true, "保护 IV；摔落保护 IV；深海探索者 III"));
            inventory.add(demoItem(-106, "minecraft:totem_of_undying", 1, false, "副手物品"));
        } else {
            inventory.add(demoItem(0, "minecraft:iron_pickaxe", 1, true, "效率 III；耐久 II"));
            inventory.add(demoItem(1, "minecraft:oak_planks", 48, false, "方块纹理回退测试"));
            inventory.add(demoItem(2, "minecraft:cooked_beef", 23, false, "食物"));
            inventory.add(demoItem(3, "minecraft:water_bucket", 1, false, "容器物品"));
            inventory.add(demoItem(9, "minecraft:coal", 64, false, "普通堆叠物品"));
            inventory.add(demoItem(10, "minecraft:torch", 64, false, "方块/物品图标测试"));
            inventory.add(demoItem(103, "minecraft:iron_helmet", 1, false, "Damage: 21"));
            inventory.add(demoItem(102, "minecraft:iron_chestplate", 1, false, "Damage: 44"));
            inventory.add(demoItem(100, "minecraft:iron_boots", 1, false, "Damage: 9"));
            inventory.add(demoItem(-106, "minecraft:shield", 1, true, "自定义旗帜图案；耐久 III"));
        }
        JsonArray ender = new JsonArray();
        ender.add(demoItem(0, "minecraft:ender_chest", 1, false, "方块物品"));
        ender.add(demoItem(1, "minecraft:diamond", bedrock ? 5 : 64, false, "贵重物品"));
        ender.add(demoItem(2, "minecraft:netherite_ingot", bedrock ? 1 : 8, false, "贵重物品"));
        ender.add(demoItem(8, "minecraft:blue_shulker_box", 1, false, "容器 NBT：建筑材料"));
        ender.add(demoItem(18, "minecraft:dragon_egg", 1, false, "稀有方块"));
        profile.add("inventoryItems", inventory);
        profile.add("enderItems", ender);
        return profile;
    }

    private static JsonObject demoItem(int slot, String id, int count, boolean enchanted, String nbt) {
        JsonObject item = new JsonObject();
        item.addProperty("slot", slot);
        item.addProperty("id", id);
        item.addProperty("count", count);
        item.addProperty("enchanted", enchanted);
        item.addProperty("nbt", nbt);
        return item;
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        codes.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt < now || entry.getValue().completed);
        attempts.entrySet().removeIf(entry -> entry.getValue().windowStart + RATE_WINDOW_MS < now);
    }

    private void checkRateLimit(String address) {
        AttemptWindow window = attempts.get(address);
        if (window != null && window.failures >= 10 && window.windowStart + RATE_WINDOW_MS >= System.currentTimeMillis()) {
            throw new PortalException("验证码尝试次数过多，请 10 分钟后再试。", 429);
        }
    }

    private void recordFailure(String address) {
        long now = System.currentTimeMillis();
        attempts.compute(address, (key, old) -> {
            if (old == null || old.windowStart + RATE_WINDOW_MS < now) return new AttemptWindow(now, 1);
            old.failures++;
            return old;
        });
    }

    private static void atomicCopy(Path source, Path target) throws IOException {
        if (!Files.exists(source)) throw new IOException("Source save does not exist: " + source);
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".mineconflux.tmp");
        Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void copyOptionalAtomic(Path source, Path target) throws IOException {
        if (Files.exists(source)) atomicCopy(source, target);
    }

    private static void copyIfExists(Path source, Path target) throws IOException {
        if (Files.exists(source)) Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String safeName(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String roundOne(float value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String formatFileTime(FileTime time) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.of("Asia/Shanghai")).format(time.toInstant());
    }

    private static boolean requirePost(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respondError(exchange, 405, "Method not allowed");
            return false;
        }
        return true;
    }

    private static JsonObject readJson(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(32 * 1024);
        JsonObject object = GSON.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
        if (object == null) throw new PortalException("请求内容无效。", 400);
        return object;
    }

    private static String getString(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) throw new PortalException("缺少参数：" + key, 400);
        return object.get(key).getAsString();
    }

    private static void respondError(HttpExchange exchange, int status, String message) throws IOException {
        JsonObject result = new JsonObject();
        result.addProperty("error", message);
        respondJson(exchange, status, result);
    }

    private static void respondJson(HttpExchange exchange, int status, JsonObject result) throws IOException {
        respondBytes(exchange, status, GSON.toJson(result).getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8");
    }

    private static void respondText(HttpExchange exchange, int status, String text, String contentType) throws IOException {
        respondBytes(exchange, status, text.getBytes(StandardCharsets.UTF_8), contentType);
    }

    private static void respondBytes(HttpExchange exchange, int status, byte[] bytes, String contentType) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'self'; img-src 'self' https://assets.mcasset.cloud data:; "
                        + "style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; frame-ancestors 'none'");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record Identity(UUID uuid, String name, String serverName, boolean bedrock, String xuid, long expiresAt) {}

    private static final class LinkSession {
        private final String token;
        private final Identity javaIdentity;
        private final Identity bedrockIdentity;
        private final long expiresAt;
        private volatile boolean completed;
        private boolean processing;

        private LinkSession(String token, Identity javaIdentity, Identity bedrockIdentity, long expiresAt) {
            this.token = token;
            this.javaIdentity = javaIdentity;
            this.bedrockIdentity = bedrockIdentity;
            this.expiresAt = expiresAt;
        }

        private synchronized boolean tryStart() {
            if (processing || completed) return false;
            processing = true;
            return true;
        }

        private synchronized void markCompleted() {
            completed = true;
        }

        private synchronized void finish() {
            processing = false;
        }
    }

    private static final class PortalException extends RuntimeException {
        private final int status;

        private PortalException(String message, int status) {
            super(message);
            this.status = status;
        }
    }

    private static final class AttemptWindow {
        private final long windowStart;
        private int failures;

        private AttemptWindow(long windowStart, int failures) {
            this.windowStart = windowStart;
            this.failures = failures;
        }
    }

    private static final class PortalConfig {
        private String bindAddress = "127.0.0.1";
        private int port = 18080;
        private int codeLifetimeMinutes = 20;
        private int sessionLifetimeMinutes = 30;
        private boolean enableDemoCodes = false;
        private String timeZone = "Asia/Shanghai";

        private void validate() {
            if (bindAddress == null || bindAddress.isBlank()) {
                throw new IllegalArgumentException("bindAddress must not be blank");
            }
            if (port < 1 || port > 65535) throw new IllegalArgumentException("port must be between 1 and 65535");
            if (codeLifetimeMinutes < 1 || codeLifetimeMinutes > 1440) {
                throw new IllegalArgumentException("codeLifetimeMinutes must be between 1 and 1440");
            }
            if (sessionLifetimeMinutes < 1 || sessionLifetimeMinutes > 1440) {
                throw new IllegalArgumentException("sessionLifetimeMinutes must be between 1 and 1440");
            }
            ZoneId.of(timeZone);
        }
    }
}
