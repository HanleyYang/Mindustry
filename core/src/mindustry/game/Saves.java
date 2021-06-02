package mindustry.game;

import arc.Core;
import arc.Events;
import arc.assets.AssetDescriptor;
import arc.files.Fi;
import arc.graphics.Texture;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Nullable;
import arc.util.Strings;
import arc.util.Time;
import arc.util.async.AsyncExecutor;
import com.dropbox.core.DbxException;
import com.dropbox.core.DbxRequestConfig;
import com.dropbox.core.util.IOUtil;
import com.dropbox.core.util.StringUtil;
import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.files.ListFolderResult;
import com.dropbox.core.v2.files.Metadata;
import mindustry.Vars;
import mindustry.core.GameState.State;
import mindustry.game.EventType.StateChangeEvent;
import mindustry.io.SaveIO;
import mindustry.io.SaveIO.SaveException;
import mindustry.io.SaveMeta;
import mindustry.io.SavePreviewLoader;
import mindustry.maps.Map;
import mindustry.type.Sector;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;

import static mindustry.Vars.*;

public class Saves{

    /** For dropbox save*/
    private static final DbxRequestConfig config = DbxRequestConfig.newBuilder("smart-home").build();
    public static final String cloudRootPath = "/mindustry";
    public static final String cloudSavePrefix = "cloud_save_";

    Seq<SaveSlot> saves = new Seq<>();
    @Nullable SaveSlot current;
    private @Nullable SaveSlot lastSectorSave;
    AsyncExecutor previewExecutor = new AsyncExecutor(1);
    private boolean saving;
    private float time;

    long totalPlaytime;
    private long lastTimestamp;

    public Saves(){
        Core.assets.setLoader(Texture.class, ".spreview", new SavePreviewLoader());

        Events.on(StateChangeEvent.class, event -> {
            if(event.to == State.menu){
                totalPlaytime = 0;
                lastTimestamp = 0;
                current = null;
            }
        });
    }

    public void load(){
        saves.clear();
        if (Core.settings.getBool("saveCloudSync", false) && Core.settings.getString("saveCloudSyncToken") != null) {
            DbxClientV2 client = new DbxClientV2(config, Core.settings.getString("saveCloudSyncToken"));
            try {
                client.files().getMetadata(cloudRootPath);
            } catch (DbxException e) {
                // root not exist
                try {
                    client.files().createFolderV2(cloudRootPath);
                } catch (DbxException dbxException) {
                    throw new RuntimeException(dbxException.getMessage(), dbxException);
                }
            }
            //dropbpx save sync
            OutputStream localOutput = null;
            InputStream localInput = null;
            try {
                ListFolderResult result = client.files().listFolder(cloudRootPath);
                HashMap<String, FileMetadata> cloudSaveFiles = new HashMap<>();
                for (Metadata metadata : result.getEntries()) {
                    if (metadata instanceof FileMetadata && metadata.getName().startsWith(cloudSavePrefix)) {
                        cloudSaveFiles.put(metadata.getName().substring(cloudSavePrefix.length()),
                                ((FileMetadata) metadata));
                    }
                }

                for(Fi file : saveDirectory.list()){
                    if(!file.name().contains("backup") && SaveIO.isSaveValid(file)){
                        if (cloudSaveFiles.containsKey(file.name())) {
                            FileMetadata metadata = cloudSaveFiles.remove(file.name());
                            long localTs = file.lastModified();
                            long cloudTs = metadata.getClientModified().getTime();
                            if (localTs == cloudTs) continue;
                            if (cloudTs > localTs) {
                                localOutput = file.write();
                                client.files().downloadBuilder(cloudRootPath + "/" + metadata.getName())
                                        .download(localOutput);
                            }
                        }
                        // cloud not exist or out of date
                        localInput = file.read();
                        client.files().uploadBuilder(cloudRootPath + "/" + cloudSavePrefix + file.name())
                                .uploadAndFinish(localInput);

                    }
                }
                // local not exist
                cloudSaveFiles.forEach((s, metadata) -> {
                    OutputStream localOutput1 = saveDirectory.child(s).write();
                    try {
                        client.files().downloadBuilder(cloudRootPath + "/" + metadata.getName())
                                .download(localOutput1);
                    } catch (DbxException | IOException e) {
                        throw new RuntimeException(e.getMessage(), e);
                    } finally {
                        IOUtil.closeQuietly(localOutput1);
                    }
                });
            } catch (DbxException | IOException e) {
                throw new RuntimeException(e.getMessage(), e);
            } finally {
                IOUtil.closeQuietly(localOutput);
                IOUtil.closeQuietly(localInput);
            }
        }

        for(Fi file : saveDirectory.list()){
            if(!file.name().contains("backup") && SaveIO.isSaveValid(file)){
                SaveSlot slot = new SaveSlot(file);
                saves.add(slot);
                slot.meta = SaveIO.getMeta(file);
            }
        }

        lastSectorSave = saves.find(s -> s.isSector() && s.getName().equals(Core.settings.getString("last-sector-save", "<none>")));

        //automatically assign sector save slots
        for(SaveSlot slot : saves){
            if(slot.getSector() != null){
                if(slot.getSector().save != null){
                    Log.warn("Sector @ has two corresponding saves: @ and @", slot.getSector(), slot.getSector().save.file, slot.file);
                }
                slot.getSector().save = slot;
            }
        }
    }

    public @Nullable SaveSlot getLastSector(){
        return lastSectorSave;
    }

    public @Nullable SaveSlot getCurrent(){
        return current;
    }

    public void update(){
        if(current != null && state.isGame()
                && !(state.isPaused() && Core.scene.hasDialog())){
            if(lastTimestamp != 0){
                totalPlaytime += Time.timeSinceMillis(lastTimestamp);
            }
            lastTimestamp = Time.millis();
        }

        if(state.isGame() && !state.gameOver && current != null && current.isAutosave()){
            time += Time.delta;
            if(time > Core.settings.getInt("saveinterval") * 60){
                saving = true;

                try{
                    current.save();
                }catch(Throwable t){
                    Log.err(t);
                }

                Time.runTask(3f, () -> saving = false);

                time = 0;
            }
        }else{
            time = 0;
        }
    }

    public long getTotalPlaytime(){
        return totalPlaytime;
    }

    public void resetSave(){
        current = null;
    }

    public boolean isSaving(){
        return saving;
    }

    public Fi getSectorFile(Sector sector){
        return saveDirectory.child("sector-" + sector.planet.name + "-" + sector.id + "." + saveExtension);
    }

    public void saveSector(Sector sector){
        if(sector.save == null){
            sector.save = new SaveSlot(getSectorFile(sector));
            sector.save.setName(sector.save.file.nameWithoutExtension());
            saves.add(sector.save);
        }
        sector.save.setAutosave(true);
        sector.save.save();
        lastSectorSave = sector.save;
        if (Core.settings.getBool("saveCloudSync", false) && Core.settings.getString("saveCloudSyncToken") != null) {
            InputStream localInput = sector.save.file.read();
            try {
                DbxClientV2 client = new DbxClientV2(config, Core.settings.getString("saveCloudSyncToken"));
                client.files().uploadBuilder(cloudRootPath + "/" + cloudSavePrefix + sector.save.file.name())
                        .uploadAndFinish(localInput);
            } catch (DbxException | IOException e) {
                throw new RuntimeException(e.getMessage(), e);
            } finally {
                IOUtil.closeQuietly(localInput);
            }
        }
        Core.settings.put("last-sector-save", sector.save.getName());
    }

    public SaveSlot addSave(String name){
        SaveSlot slot = new SaveSlot(getNextSlotFile());
        slot.setName(name);
        saves.add(slot);
        slot.save();
        return slot;
    }

    public SaveSlot importSave(Fi file) throws IOException{
        SaveSlot slot = new SaveSlot(getNextSlotFile());
        slot.importFile(file);
        slot.setName(file.nameWithoutExtension());
        saves.add(slot);
        slot.meta = SaveIO.getMeta(slot.file);
        current = slot;
        return slot;
    }

    public Fi getNextSlotFile(){
        int i = 0;
        Fi file;
        while((file = saveDirectory.child(i + "." + saveExtension)).exists()){
            i ++;
        }
        return file;
    }

    public Seq<SaveSlot> getSaveSlots(){
        return saves;
    }

    public void deleteAll(){
        for(SaveSlot slot : saves.copy()){
            if(!slot.isSector()){
                slot.delete();
            }
        }
    }

    public class SaveSlot{
        public final Fi file;
        boolean requestedPreview;
        public SaveMeta meta;

        public SaveSlot(Fi file){
            this.file = file;
        }

        public void load() throws SaveException{
            try{
                SaveIO.load(file);
                meta = SaveIO.getMeta(file);
                current = this;
                totalPlaytime = meta.timePlayed;
                savePreview();
            }catch(Throwable e){
                throw new SaveException(e);
            }
        }

        public void save(){
            long time = totalPlaytime;
            long prev = totalPlaytime;
            totalPlaytime = time;

            SaveIO.save(file);
            meta = SaveIO.getMeta(file);
            if(state.isGame()){
                current = this;
            }

            totalPlaytime = prev;
            savePreview();
        }

        private void savePreview(){
            if(Core.assets.isLoaded(loadPreviewFile().path())){
                Core.assets.unload(loadPreviewFile().path());
            }
            previewExecutor.submit(() -> {
                try{
                    previewFile().writePNG(renderer.minimap.getPixmap());
                    requestedPreview = false;
                }catch(Throwable t){
                    Log.err(t);
                }
            });
        }

        public Texture previewTexture(){
            if(!previewFile().exists()){
                return null;
            }else if(Core.assets.isLoaded(loadPreviewFile().path())){
                return Core.assets.get(loadPreviewFile().path());
            }else if(!requestedPreview){
                Core.assets.load(new AssetDescriptor<>(loadPreviewFile(), Texture.class));
                requestedPreview = true;
            }
            return null;
        }

        private String index(){
            return file.nameWithoutExtension();
        }

        private Fi previewFile(){
            return mapPreviewDirectory.child("save_slot_" + index() + ".png");
        }

        private Fi loadPreviewFile(){
            return previewFile().sibling(previewFile().name() + ".spreview");
        }

        public boolean isHidden(){
            return isSector();
        }

        public String getPlayTime(){
            return Strings.formatMillis(current == this ? totalPlaytime : meta.timePlayed);
        }

        public long getTimestamp(){
            return meta.timestamp;
        }

        public String getDate(){
            return SimpleDateFormat.getDateTimeInstance().format(new Date(meta.timestamp));
        }

        public Map getMap(){
            return meta.map;
        }

        public void cautiousLoad(Runnable run){
            Seq<String> mods = Seq.with(getMods());
            mods.removeAll(Vars.mods.getModStrings());

            if(!mods.isEmpty()){
                ui.showConfirm("@warning", Core.bundle.format("mod.missing", mods.toString("\n")), run);
            }else{
                run.run();
            }
        }

        public String getName(){
            return Core.settings.getString("save-" + index() + "-name", "untitled");
        }

        public void setName(String name){
            Core.settings.put("save-" + index() + "-name", name);
        }

        public String[] getMods(){
            return meta.mods;
        }

        public Sector getSector(){
            return meta == null || meta.rules == null ? null : meta.rules.sector;
        }

        public boolean isSector(){
            return getSector() != null;
        }

        public Gamemode mode(){
            return meta.rules.mode();
        }

        public int getBuild(){
            return meta.build;
        }

        public int getWave(){
            return meta.wave;
        }

        public boolean isAutosave(){
            return Core.settings.getBool("save-" + index() + "-autosave", true);
        }

        public void setAutosave(boolean save){
            Core.settings.put("save-" + index() + "-autosave", save);
        }

        public void importFile(Fi from) throws IOException{
            try{
                from.copyTo(file);
            }catch(Exception e){
                throw new IOException(e);
            }
        }

        public void exportFile(Fi to) throws IOException{
            try{
                file.copyTo(to);
            }catch(Exception e){
                throw new IOException(e);
            }
        }

        public void delete(){
            if(SaveIO.backupFileFor(file).exists()){
                SaveIO.backupFileFor(file).delete();
            }
            file.delete();
            saves.remove(this, true);
            if(this == current){
                current = null;
            }

            if(Core.assets.isLoaded(loadPreviewFile().path())){
                Core.assets.unload(loadPreviewFile().path());
            }
        }
    }
}