package dev.nova.novadebug.modules;
import dev.nova.novadebug.rendering.ChunkRenderer;
import dev.nova.novadebug.util.ChunkActivityData;
import dev.nova.novadebug.util.ChunkPos2D;
import dev.nova.novadebug.util.ChunkTracker;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import java.util.Map;
public class GoofyDebug extends Module {
    private final SettingGroup sgGeneral     = settings.getDefaultGroup();
    private final SettingGroup sgDetection   = settings.createGroup("Detection");
    private final SettingGroup sgRender      = settings.createGroup("Render");
    private final SettingGroup sgPerformance = settings.createGroup("Performance");
    public enum Sensitivity {
        Low,Medium,High,Extreme;
        public float multiplier() { return switch(this){case Low->0.5f;case Medium->1.0f;case High->1.75f;default->3.0f;}; }
        public float minScore()   { return switch(this){case Low->30f;case Medium->15f;case High->7f;default->2f;}; }
    }
    public enum ChunkHeightMode { UndergroundOnly, FullColumn }
    private final Setting<Sensitivity> sensitivity=sgGeneral.add(new EnumSetting.Builder<Sensitivity>().name("sensitivity").description("Detection sensitivity").defaultValue(Sensitivity.Medium).build());
    private final Setting<Boolean> detectSpawners=sgDetection.add(new BoolSetting.Builder().name("skeleton-spawners").description("Detect skeleton spawners underground").defaultValue(true).build());
    private final Setting<Boolean> detectChunkActivity=sgDetection.add(new BoolSetting.Builder().name("player-activity").description("Score chunks on repeated load events").defaultValue(true).build());
    private final Setting<Boolean> detectPlayers=sgDetection.add(new BoolSetting.Builder().name("players").description("Flag chunks containing other players").defaultValue(true).build());
    private final Setting<SettingColor> lowColor=sgRender.add(new ColorSetting.Builder().name("low-activity-color").description("Low activity color").defaultValue(new SettingColor(0,255,0,180)).build());
    private final Setting<SettingColor> medColor=sgRender.add(new ColorSetting.Builder().name("medium-activity-color").description("Medium activity color").defaultValue(new SettingColor(255,165,0,200)).build());
    private final Setting<SettingColor> highColor=sgRender.add(new ColorSetting.Builder().name("high-activity-color").description("High activity color").defaultValue(new SettingColor(255,0,0,220)).build());
    private final Setting<Integer> renderDistance=sgRender.add(new IntSetting.Builder().name("render-distance").description("Render distance in chunks").defaultValue(8).min(2).sliderMax(20).build());
    private final Setting<Boolean> filledChunks=sgRender.add(new BoolSetting.Builder().name("filled-chunks").description("Fill chunks with transparent color").defaultValue(false).build());
    private final Setting<Integer> outlineAlpha=sgRender.add(new IntSetting.Builder().name("outline-alpha").description("Outline opacity 0-255").defaultValue(180).min(10).sliderMax(255).build());
    private final Setting<ChunkHeightMode> chunkHeightMode=sgRender.add(new EnumSetting.Builder<ChunkHeightMode>().name("chunk-height-mode").description("Vertical extent of highlights").defaultValue(ChunkHeightMode.UndergroundOnly).build());
    private final Setting<Integer> updateInterval=sgPerformance.add(new IntSetting.Builder().name("update-interval").description("Ticks between analysis passes").defaultValue(20).min(5).sliderMax(100).build());
    private final Setting<Integer> maxTrackedChunks=sgPerformance.add(new IntSetting.Builder().name("max-tracked-chunks").description("Max tracked chunks").defaultValue(512).min(64).sliderMax(2048).build());
    private final Setting<Double> decaySpeed=sgPerformance.add(new DoubleSetting.Builder().name("activity-decay-speed").description("Score decay rate").defaultValue(0.5).min(0.1).sliderMax(5.0).build());
    private ChunkTracker tracker;
    public GoofyDebug() {
        super(Categories.World,"Nova Debug","Chunk-based underground activity analysis by Saint.");
    }
    @Override public void onActivate()   { tracker=new ChunkTracker(); info("Nova Debug v2 by Saint - active."); }
    @Override public void onDeactivate() { if(tracker!=null){tracker.reset();tracker=null;} }
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if(tracker==null) return;
        Sensitivity s=sensitivity.get();
        tracker.tick(new ChunkTracker.Settings(updateInterval.get(),maxTrackedChunks.get(),decaySpeed.get().floatValue(),s.multiplier(),detectSpawners.get(),detectChunkActivity.get(),detectPlayers.get(),s.minScore()));
    }
    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if(tracker==null) return;
        Map<ChunkPos2D,ChunkActivityData> chunks=tracker.getTrackedChunks();
        if(chunks.isEmpty()) return;
        Sensitivity s=sensitivity.get();
        ChunkRenderer.RenderSettings rs=new ChunkRenderer.RenderSettings(
            c(lowColor.get()),c(medColor.get()),c(highColor.get()),
            renderDistance.get(),filledChunks.get(),outlineAlpha.get(),
            chunkHeightMode.get()==ChunkHeightMode.UndergroundOnly,s.minScore());
        ChunkRenderer.render(event.renderer,chunks,rs);
    }
    private Color c(SettingColor sc){return new Color(sc.r,sc.g,sc.b,sc.a);}
}
