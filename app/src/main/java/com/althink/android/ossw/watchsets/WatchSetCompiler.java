package com.althink.android.ossw.watchsets;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Base64;

import com.althink.android.ossw.plugins.PluginDefinition;
import com.althink.android.ossw.plugins.PluginManager;
import com.althink.android.ossw.service.WatchExtensionFunction;
import com.althink.android.ossw.service.WatchExtensionProperty;
import com.althink.android.ossw.service.WatchOperationContext;
import com.althink.android.ossw.watch.WatchConstants;
import com.althink.android.ossw.watchsets.field.EnumFieldDefinition;
import com.althink.android.ossw.watchsets.field.FieldDefinition;
import com.althink.android.ossw.watchsets.field.IntegerFieldDefinition;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Created by krzysiek on 14/06/15.
 */
public class WatchSetCompiler {

    private Map<String, Integer> screenIdToNumber = new HashMap<>();

    private Map<String, Integer> resourceIdToNumber = new HashMap<>();

    private final static String TAG = WatchSetCompiler.class.getSimpleName();

    private List<WatchExtensionProperty> extensionParameters = new LinkedList<>();

    private List<WatchExtensionFunction> extensionFunctions = new LinkedList<>();

    private Map<String, PluginDefinition> plugins;

    private Context context;

    public WatchSetCompiler(Context context) {
        this.context = context;
    }

    public CompiledWatchSet compile(String watchSetSource, Integer extWatchSetId) {

        plugins = new HashMap<>();
        List<PluginDefinition> pluginList = new PluginManager(context).findPlugins();
        for (PluginDefinition plugin : pluginList) {
            plugins.put(plugin.getPluginId(), plugin);
        }

        try {
            JSONObject jsonObject = new JSONObject(watchSetSource);

            String type = jsonObject.getString("type");
            if (!"watchset".equals(type)) {
                throw new KnownParseError("Invalid file type");
            }

            int apiVersion = jsonObject.getInt("apiVersion");
            if (apiVersion < 1 || apiVersion > 2) {
                throw new KnownParseError("Invalid api version");
            }
            String watchsetName = jsonObject.getString("name");

            JSONObject data = jsonObject.getJSONObject("data");

            JSONArray resources = data.optJSONArray("resources");
            if (resources != null) {
                parseResources(resources);
            }

            JSONArray screens = data.getJSONArray("screens");

            if (screens.length() > 255) {
                throw new KnownParseError("Invalid number of screens");
            }

            byte[] screensData = compileScreensSection(screens);

            byte[] extensionPropertiesData = compileExternalProperties();

            CompiledWatchSet watchset = new CompiledWatchSet();
            if (extWatchSetId != null) {
                watchset.setId(extWatchSetId);
            } else {
                watchset.setId(generateWatchSetId());
            }

            ByteArrayOutputStream os = new ByteArrayOutputStream();
            // magic number
            os.write(0x05);
            os.write(0x53);
            // format version
            os.write(0);
            os.write(1);
            // write watch set id
            os.write(watchset.getId() >> 24);
            os.write(watchset.getId() >> 16 & 0xFF);
            os.write(watchset.getId() >> 8 & 0xFF);
            os.write(watchset.getId() & 0xFF);

            os.write(WatchConstants.WATCH_SET_SECTION_SCREENS);

            os.write(screensData.length >> 8);
            os.write(screensData.length & 0xFF);
            os.write(screensData);

            // write external properties info
            os.write(WatchConstants.WATCH_SET_SECTION_EXTERNAL_PROPERTIES);
            os.write(extensionPropertiesData.length >> 8);
            os.write(extensionPropertiesData.length & 0xFF);
            os.write(extensionPropertiesData);

            if (resources != null) {
                byte[] resourcesData = compileResources(resources);
                os.write(WatchConstants.WATCH_SET_SECTION_RESOURCES);
                os.write(resourcesData.length >> 8);
                os.write(resourcesData.length & 0xFF);
                os.write(resourcesData);
            }

            os.write(WatchConstants.WATCH_SET_END_OF_DATA);

            watchset.setName(watchsetName);
            watchset.setWatchContext(new WatchOperationContext(extensionParameters, extensionFunctions));
            watchset.setWatchData(os.toByteArray());

            //Log.i(TAG, "size: " + watchset.getWatchData().length + ", data: " + Arrays.toString(watchset.getWatchData()));

            return watchset;
        } catch (KnownParseError e) {
            throw e;
        } catch (Exception e) {
            //Log.e(TAG, e.getMessage(), e);
            throw new KnownParseError("JSON format error", e);
        }
    }

    private byte[] compileResources(JSONArray resources) throws JSONException, IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        os.write(resources.length());

        LinkedList<byte[]> resourcesData = new LinkedList<>();

        int resourceDataOffset = 1 + (resources.length() * 3);
        for (int resNo = 0; resNo < resources.length(); resNo++) {
            JSONObject resource = resources.getJSONObject(resNo);

            byte[] resourceData = Base64.decode(resource.getString("data"), Base64.DEFAULT);
            resourcesData.add(resourceData);

            // write resource start address
            os.write((resourceDataOffset >> 16) & 0xFF);
            os.write((resourceDataOffset >> 8) & 0xFF);
            os.write(resourceDataOffset & 0xFF);
            resourceDataOffset += resourceData.length;
        }

        // write resources data
        for (byte[] resourceData : resourcesData) {
            os.write(resourceData);
        }

        return os.toByteArray();
    }

    private void parseResources(JSONArray resources) throws JSONException {
        if (resources.length() > 256) {
            throw new KnownParseError("Too many resources");
        }
        for (int i = 0; i < resources.length(); i++) {
            JSONObject resource = resources.getJSONObject(i);
            resourceIdToNumber.put(resource.getString("id"), i);
        }
    }

    private int generateWatchSetId() {
        String prefKey = "next_watchset_id";
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
        int value = sharedPref.getInt(prefKey, 1);
        sharedPref.edit().putInt(prefKey, value + 1).commit();
        return value;
    }

    private byte[] compileExternalProperties() {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        os.write(extensionParameters.size());
        for (WatchExtensionProperty property : extensionParameters) {
            //write parameter info
            os.write(property.getType().getKey());
            os.write(property.getRange());
        }
        return os.toByteArray();
    }

    private byte[] compileScreensSection(JSONArray screens) throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();

        // write number of screens
        os.write(screens.length());

        screenIdToNumber.clear();
        extensionParameters.clear();
        extensionFunctions.clear();

        // in first round parse only screen ids
        for (int scrNo = 0; scrNo < screens.length(); scrNo++) {
            JSONObject screen = screens.getJSONObject(scrNo);
            String screenId = screen.getString("id");

            if (screenIdToNumber.containsKey("screenId")) {
                throw new KnownParseError("Screen " + screenId + " is already defined");
            }
            screenIdToNumber.put(screenId, scrNo);
        }

        LinkedList<byte[]> screensData = new LinkedList<>();
        // parse screen controls and actions / write screen table
        int screenOffset = 1 + (screens.length() * 2);
        for (int scrNo = 0; scrNo < screens.length(); scrNo++) {
            JSONObject screen = screens.getJSONObject(scrNo);

            byte[] screenData = parseScreen(screen);
            screensData.add(screenData);

            // write screen start address
            os.write((screenOffset >> 8) & 0xFF);
            os.write(screenOffset & 0xFF);
            screenOffset += screenData.length;
        }

        // write screens data
        for (byte[] screenData : screensData) {
            os.write(screenData);
        }
        return os.toByteArray();
    }

    private byte[] parseScreen(JSONObject screen) throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();

        ScreenContext screenContext = new ScreenContext();

        JSONObject model = screen.optJSONObject("model");
        if (model != null) {
            os.write(WatchConstants.WATCH_SET_SCREEN_SECTION_MODEL);


            byte[] modelData = parseModel(screenContext, model);
            os.write(modelData.length >> 8);
            os.write(modelData.length & 0xFF);
            os.write(modelData);
        }

        JSONArray controls = screen.getJSONArray("controls");

        if (controls == null && controls.length() == 0) {
            throw new KnownParseError("Empty screen");
        }

        if (controls.length() > 255) {
            throw new KnownParseError("Too many controls, 255 is max");
        }

        os.write(WatchConstants.WATCH_SET_SCREEN_SECTION_CONTROLS);
        byte[] screenControlsData = parseScreenControls(controls, screenContext);
        os.write((screenControlsData.length >> 8) & 0xFF);
        os.write(screenControlsData.length & 0xFF);
        os.write(screenControlsData);

        JSONObject actions = screen.optJSONObject("actions");

        if (actions != null) {
            os.write(WatchConstants.WATCH_SET_SCREEN_SECTION_ACTIONS);
            parseEventHandlers(os, actions, screenContext);
        }

        String defaultActions = screen.optString("defaultActions", null);
        if (defaultActions != null) {
            os.write(WatchConstants.WATCH_SET_SCREEN_SECTION_BASE_ACTIONS);
            switch (defaultActions) {
                case "watchface":
                    os.write(1);
                    break;
                default:
                    throw new KnownParseError("Invalid default actions: " + defaultActions);
            }
        }

        os.write(WatchConstants.WATCH_SET_SCREEN_SECTION_MEMORY);
        int size = screenContext.getAllocator().getSize();
        os.write((size >> 8) & 0xFF);
        os.write(size & 0xFF);

        JSONObject settings = screen.optJSONObject("settings");
        if (settings != null) {
            os.write(WatchConstants.WATCH_SET_SCREEN_SECTION_SETTINGS);
            byte[] data = parseSettings(settings);
            os.write((data.length >> 8) & 0xFF);
            os.write(data.length & 0xFF);
            os.write(data);
        }

        os.write(WatchConstants.WATCH_SET_END_OF_DATA);
        return os.toByteArray();
    }

    private byte[] parseSettings(JSONObject settings) throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        if (settings.length() > 256) {
            throw new KnownParseError("Too many settings");
        }
        os.write(settings.length());
        for (Iterator<String> i = settings.keys(); i.hasNext(); ) {
            String optionKey = i.next();

            switch(optionKey) {
                case "invertible":
                    os.write(WatchConstants.WATCH_SET_SETTING_INVERTIBLE);
                    os.write(settings.getBoolean("invertible")?1:0);
                    break;
                default:
                    throw new KnownParseError("Unknown setting: " + optionKey);
            }
        }
        return os.toByteArray();
    }

    private void parseEventHandlers(ByteArrayOutputStream os, JSONObject actions, ScreenContext screenContext) throws Exception {

        Map<Integer, byte[]> handlers = new HashMap<>();
        if (actions.has("choose")) {
            // handle top level choose
            JSONObject choose = (JSONObject) actions.remove("choose");
            JSONObject when = (JSONObject) actions.remove("when");
            Set<String> conditionEvents = new HashSet<>();
            for (Iterator<String> i = when.keys(); i.hasNext(); ) {
                String optionKey = i.next();
                JSONObject events = when.getJSONObject(optionKey);
                for (Iterator<String> e = events.keys(); e.hasNext(); ) {
                    conditionEvents.add(e.next());
                }
            }

            for (String eventKey : conditionEvents) {
                JSONObject fakeWhen = new JSONObject();

                ByteArrayOutputStream eventOs = new ByteArrayOutputStream();

                for (Iterator<String> i = when.keys(); i.hasNext(); ) {
                    String optionKey = i.next();
                    JSONObject optionEvents = when.getJSONObject(optionKey);
                    if (optionEvents.has(eventKey)) {
                        fakeWhen.put(optionKey, optionEvents.get(eventKey));
                    }
                }
                eventOs.write(1);
                buildChooseData(eventOs, choose, fakeWhen, screenContext);

                handlers.put(getEventId(eventKey), eventOs.toByteArray());
            }

        }
        Iterator<String> events = actions.keys();
        while (events.hasNext()) {
            String eventKey = events.next();
            if (handlers.containsKey(getEventId(eventKey))) {
                throw new KnownParseError("Event: " + eventKey + " already handled!");
            }
            handlers.put(getEventId(eventKey), parseActions(actions.get(eventKey), screenContext));
        }


        ByteArrayOutputStream indexOs = new ByteArrayOutputStream();
        indexOs.write(handlers.size());
        ByteArrayOutputStream dataOs = new ByteArrayOutputStream();
        for(Map.Entry<Integer, byte[]> entry : handlers.entrySet()) {
            indexOs.write(entry.getKey());
            indexOs.write(dataOs.size()>>8);
            indexOs.write(dataOs.size()&0xFF);
            dataOs.write(entry.getValue());
        }


        int size = indexOs.size() + dataOs.size();
        os.write(size>>8);
        os.write(size&0xFF);
        os.write(indexOs.toByteArray());
        os.write(dataOs.toByteArray());
    }

    private byte[] parseModel(ScreenContext screenContext, JSONObject model) throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        JSONArray names = model.names();
        os.write(names.length());
        for (int i = 0; i < names.length(); i++) {
            String fieldName = names.getString(i);
            os.write(parseFieldDefinition(i, fieldName, model.getJSONObject(fieldName), screenContext));
        }
        return os.toByteArray();
    }

    private byte[] parseFieldDefinition(int filedId, String fieldName, JSONObject field, ScreenContext screenContext) throws Exception {
        switch (field.getString("type")) {
            case "integer":
                return parseIntegerFieldDefinition(filedId, fieldName, field, screenContext);
            case "enum":
                return parseEnumFieldDefinition(filedId, fieldName, field, screenContext);
            default:
                throw new KnownParseError("Invalid field type: " + field.getString("type"));
        }
    }

    private byte[] parseEnumFieldDefinition(int filedId, String fieldName, JSONObject field, ScreenContext screenContext) throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        JSONArray values = field.getJSONArray("values");
        Map<String, Integer> valuesMap = new HashMap<>();
        for (int i = 0; i < values.length(); i++) {
            valuesMap.put(values.getString(i), i);
        }
        EnumFieldDefinition definition = new EnumFieldDefinition(filedId, valuesMap);

        DataSourceResolutionContext resCtx = new DataSourceResolutionContext(screenContext);
        resCtx.dataSourceType = DataSourceType.NUMBER;
        resCtx.resolver = new EnumToIntResolver(valuesMap);

        JSONObject initValue = field.optJSONObject("value");
        boolean overflow = field.optBoolean("overflow", false);

        screenContext.getModel().put(fieldName, definition);

        os.write(1);
        int flags = 0x20 | 0x10;
        if (initValue != null) {
            flags |= 0x80;
        }
        if (overflow) {
            flags |= 0x40;
        }

        os.write(flags);

        if (initValue != null) {
            DataSourceResolutionContext info = new DataSourceResolutionContext(screenContext);
            info.dataSourceType = DataSourceType.NUMBER;
            info.resolver = definition;
            os.write(compileSource(initValue, info));
        }

        //max
        DataSourceResolutionContext context = new DataSourceResolutionContext(screenContext);
        context.dataSourceType = DataSourceType.NUMBER;
        context.dataRange = 0;
        JSONObject source = new JSONObject();
        source.put("type", "static");
        source.put("value", values.length() - 1);
        os.write(compileSource(source, context));
        //min
        source.put("value", 0);
        os.write(compileSource(source, context));
        return os.toByteArray();
    }

    private byte[] parseIntegerFieldDefinition(int filedId, String fieldName, JSONObject field, ScreenContext screenContext) throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        IntegerFieldDefinition definition = new IntegerFieldDefinition(filedId);
        JSONObject min = field.optJSONObject("min");
        JSONObject max = field.optJSONObject("max");
        boolean overflow = field.optBoolean("overflow", false);
        JSONObject initValue = field.optJSONObject("value");

        screenContext.getModel().put(fieldName, definition);

        os.write(1);// type int
        int flags = 0;
        if (initValue != null) {
            flags |= 0x80;
        }
        if (overflow) {
            flags |= 0x40;
        }
        if (max != null) {
            flags |= 0x20;
        }
        if (min != null) {
            flags |= 0x10;
        }

        os.write(flags);

        if (initValue != null) {
            DataSourceResolutionContext info = new DataSourceResolutionContext(screenContext);
            info.dataSourceType = DataSourceType.NUMBER;
            os.write(compileSource(initValue, info));
        }

        if (max != null) {
            DataSourceResolutionContext context = new DataSourceResolutionContext(screenContext);
            context.dataSourceType = DataSourceType.NUMBER;
            os.write(compileSource(max, context));
        }
        if (min != null) {
            DataSourceResolutionContext context = new DataSourceResolutionContext(screenContext);
            context.dataSourceType = DataSourceType.NUMBER;
            os.write(compileSource(min, context));
        }
        return os.toByteArray();
    }

    private byte[] parseScreenControls(Object controls, ScreenContext screenContext) throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        if (controls instanceof JSONArray) {
            os.write(parseScreenControls((JSONArray) controls, screenContext));
        } else if (controls instanceof JSONObject) {
            os.write(1);
            os.write(parseScreenControl((JSONObject) controls, screenContext));
        }
        return os.toByteArray();
    }

    private byte[] parseScreenControls(JSONArray controls, ScreenContext screenContext) throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        os.write(controls.length());

        for (int controlNo = 0; controlNo < controls.length(); controlNo++) {
            JSONObject control = controls.getJSONObject(controlNo);
            os.write(parseScreenControl(control, screenContext));
        }
        return os.toByteArray();
    }

    private byte[] parseScreenControl(JSONObject control, ScreenContext screenContext) throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        if (control.has("type")) {
            os.write(compileControl(control, screenContext));
        } else if (control.has("choose")) {
            os.write(compileScreenControlChoose(control, screenContext));
        } else {
            throw new KnownParseError("Invalid screen control");
        }
        return os.toByteArray();
    }

    private byte[] parseActions(Object config, ScreenContext screenContext) throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        if (config instanceof JSONArray) {
            os.write(compileActions((JSONArray) config, screenContext));
        } else if (config instanceof JSONObject) {
            os.write(1);
            os.write(compileAction((JSONObject) config, screenContext));
        } else {
            throw new KnownParseError("Invalid actions");
        }
        return os.toByteArray();
    }

    private byte[] compileActions(JSONArray actions, ScreenContext screenContext) throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        os.write(actions.length());

        for (int controlNo = 0; controlNo < actions.length(); controlNo++) {
            JSONObject control = actions.getJSONObject(controlNo);
            os.write(compileAction(control, screenContext));
        }
        return os.toByteArray();
    }

    private byte[] compileAction(JSONObject action, ScreenContext screenContext) throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        if (action.has("action")) {
            os.write(compileStandardAction(action, screenContext));
        } else if (action.has("choose")) {
            os.write(compileActionChoose(action, screenContext));
        } else {
            throw new KnownParseError("Invalid screen control");
        }
        return os.toByteArray();
    }

    private byte[] compileActionChoose(JSONObject config, ScreenContext screenContext) throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        JSONObject choose = config.getJSONObject("choose");
        JSONObject when = config.getJSONObject("when");
        buildChooseData(os, choose, when, screenContext);
        return os.toByteArray();
    }

    private void buildChooseData(ByteArrayOutputStream os, JSONObject choose, JSONObject when, ScreenContext screenContext) throws Exception {
        DataSourceResolutionContext resCtx = new DataSourceResolutionContext(screenContext);
        os.write(WatchConstants.WATCHSET_FUNCTION_CHOOSE);
        os.write(compileSource(choose, resCtx));
        os.write(when.length());
        JSONArray options = when.names();
        for (int i = 0; i < options.length(); i++) {
            String optionName = options.getString(i);
            if (resCtx.resolver == null) {
                throw new KnownParseError("Choose value not supported");
            }
            os.write((int) resCtx.resolver.resolve(optionName));
            Object actions = when.get(optionName);
            byte[] data = parseActions(actions, screenContext);
            os.write(data.length>>8);
            os.write(data.length&0xFF);
            os.write(data);
        }
    }

    private byte[] compileStandardAction(JSONObject config, ScreenContext screenContext) throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();

        String action = config.getString("action");

        switch (action) {
            case "extensionFunction":
                String extensionId = config.getString("extensionId");
                String function = config.getString("function");
                String parameter = config.optString("parameter");
                writeActionWithParam(os, WatchConstants.WATCHSET_FUNCTION_EXTENSION, addExtensionFunction(new WatchExtensionFunction(extensionId, function, parameter)));
                break;
            case "toggleBacklight":
                writeSimpleAction(os, WatchConstants.WATCHSET_FUNCTION_TOGGLE_BACKLIGHT);
                break;
            case "stopwatch.start":
                writeSimpleAction(os, WatchConstants.WATCHSET_FUNCTION_STOPWATCH_START);
                break;
            case "stopwatch.stop":
                writeSimpleAction(os, WatchConstants.WATCHSET_FUNCTION_STOPWATCH_STOP);
                break;
            case "stopwatch.reset":
                writeSimpleAction(os, WatchConstants.WATCHSET_FUNCTION_STOPWATCH_RESET);
                break;
            case "stopwatch.startStop":
                writeSimpleAction(os, WatchConstants.WATCHSET_FUNCTION_STOPWATCH_START_STOP);
                break;
            case "stopwatch.nextLap":
                writeSimpleAction(os, WatchConstants.WATCHSET_FUNCTION_STOPWATCH_NEXT_LAP);
                break;
            case "toggleColors":
                writeSimpleAction(os, WatchConstants.WATCHSET_FUNCTION_TOGGLE_COLORS);
                break;
            case "showScreen":
                writeActionWithParam(os, WatchConstants.WATCHSET_FUNCTION_CHANGE_SCREEN, resolveScreenId(config.getString("screenId")));
                break;
            case "close":
                writeSimpleAction(os, WatchConstants.WATCHSET_FUNCTION_CLOSE);
                break;
            case "settings":
                writeSimpleAction(os, WatchConstants.WATCHSET_FUNCTION_SHOW_SETTINGS);
                break;
            default:
                if (action.startsWith("model.")) {
                    String parts[] = action.split("\\.");
                    if (parts.length != 3) {
                        throw new KnownParseError("Invalid action: " + action);
                    }
                    FieldDefinition definition = screenContext.getModel().get(parts[1]);
                    if (definition == null) {
                        throw new KnownParseError("Invalid field name in action: " + action);
                    }
                    DataSourceResolutionContext dsCtx = new DataSourceResolutionContext(screenContext);
                    dsCtx.dataSourceType = DataSourceType.NUMBER;
                    dsCtx.resolver = definition;
                    switch (parts[2]) {
                        case "set":
                            writeModelAction(os, WatchConstants.WATCHSET_FUNCTION_MODEL_SET, definition.getFieldId(), compileSource(config.getJSONObject("value"), dsCtx));
                            break;
                        case "add":
                            writeModelAction(os, WatchConstants.WATCHSET_FUNCTION_MODEL_ADD, definition.getFieldId(), compileSource(config.getJSONObject("value"), dsCtx));
                            break;
                        case "subtract":
                            writeModelAction(os, WatchConstants.WATCHSET_FUNCTION_MODEL_SUBTRACT, definition.getFieldId(), compileSource(config.getJSONObject("value"), dsCtx));
                            break;
                        case "increment":
                            writeModelAction(os, WatchConstants.WATCHSET_FUNCTION_MODEL_INCREMENT, definition.getFieldId());
                            break;
                        case "decrement":
                            writeModelAction(os, WatchConstants.WATCHSET_FUNCTION_MODEL_DECREMENT, definition.getFieldId());
                            break;
                        default:
                            throw new KnownParseError("Invalid action: " + action);

                    }
                } else {
                    throw new KnownParseError("Invalid action: " + action);
                }
        }
        return os.toByteArray();
    }

    private void writeModelAction(ByteArrayOutputStream os, int functionId, int fieldId, byte[] dataSource) throws Exception {
        os.write(functionId);
        os.write(fieldId & 0xFF);
        if (dataSource != null) {
            os.write(dataSource);
        }
    }

    private void writeModelAction(ByteArrayOutputStream os, int functionId, int fieldId) throws Exception {
        writeModelAction(os, functionId, fieldId, null);
    }

    private void writeSimpleAction(ByteArrayOutputStream os, int functionId) {
        os.write(functionId);
    }

    private void writeActionWithParam(ByteArrayOutputStream os, int functionId, int parameter) {
        os.write(functionId);
        os.write(parameter >> 8);
        os.write(parameter & 0xFF);
    }

    private int addExtensionFunction(WatchExtensionFunction extensionFunction) {
        int funcIdx = extensionFunctions.indexOf(extensionFunction);
        if (funcIdx < 0) {
            extensionFunctions.add(extensionFunction);
            return extensionFunctions.size() - 1;
        } else {
            return funcIdx;
        }
    }

    private int resolveScreenId(String screenId) {
        Integer screenNo = screenIdToNumber.get(screenId);
        if (screenNo == null) {
            throw new KnownParseError("Screen is not defined: " + screenId);
        }
        return screenNo;
    }

    private int getEventId(String eventKey) {
        switch (eventKey) {
            case "button_up_short":
            case "buttons.up.short":
                return WatchConstants.EVENT_BUTTON_UP_SHORT;
            case "button_select_short":
            case "buttons.select.short":
                return WatchConstants.EVENT_BUTTON_SELECT_SHORT;
            case "button_down_short":
            case "buttons.down.short":
                return WatchConstants.EVENT_BUTTON_DOWN_SHORT;
            case "button_back_short":
            case "buttons.back.short":
                return WatchConstants.EVENT_BUTTON_BACK_SHORT;
            case "button_up_long":
            case "buttons.up.long":
                return WatchConstants.EVENT_BUTTON_UP_LONG;
            case "button_select_long":
            case "buttons.select.long":
                return WatchConstants.EVENT_BUTTON_SELECT_LONG;
            case "button_down_long":
            case "buttons.down.long":
                return WatchConstants.EVENT_BUTTON_DOWN_LONG;
            case "button_back_long":
            case "buttons.back.long":
                return WatchConstants.EVENT_BUTTON_BACK_LONG;
        }
        throw new KnownParseError("Unknown event key: " + eventKey);
    }

    private byte[] compileControl(JSONObject control, ScreenContext screenContext) throws Exception {
        String controlType = control.getString("type");

        switch (controlType) {
            case "number":
                return compileNumberControl(control, screenContext);
            case "text":
                return compileTextControl(control, screenContext);
            case "progress":
                return compileProgressControl(control, screenContext);
            case "image":
                return compileImageControl(control, screenContext);
            case "imageFromSet":
                return compileImageFromSetControl(control, screenContext);
        }
        throw new KnownParseError("Not supported control type: " + controlType);
    }

    private byte[] compileScreenControlChoose(JSONObject object, ScreenContext screenContext) throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        JSONObject choose = object.getJSONObject("choose");
        JSONObject when = object.getJSONObject("when");
        DataSourceResolutionContext resCtx = new DataSourceResolutionContext(screenContext);
        resCtx.dataSourceType = DataSourceType.NUMBER;
        os.write(WatchConstants.SCR_CONTROL_CHOOSE);
        os.write(compileSource(choose, resCtx));
        int buffer = screenContext.getAllocator().addBuffer(1);
        os.write(buffer<<8);
        os.write(buffer&0xFF);
        os.write(when.length());
        JSONArray options = when.names();
        for (int i = 0; i < options.length(); i++) {
            String optionName = options.getString(i);
            if (resCtx.resolver != null) {
                os.write((int) resCtx.resolver.resolve(optionName));
            } else {
                os.write((int) Integer.parseInt(optionName));
            }
            Object option = when.get(optionName);
            byte[] data = parseScreenControls(option, screenContext);
            os.write(data.length >> 8);
            os.write(data.length & 0xFF);
            os.write(data);
        }
        return os.toByteArray();
    }

    private byte[] compileProgressControl(JSONObject control, ScreenContext screenContext) throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();

        os.write(WatchConstants.SCR_CONTROL_HORIZONTAL_PROGRESS_BAR);
        int maxValue = control.getInt("maxValue");
        os.write(maxValue >> 24);
        os.write(maxValue >> 16 & 0xFF);
        os.write(maxValue >> 8 & 0xFF);
        os.write(maxValue & 0xFF);
        JSONObject position = control.getJSONObject("position");
        os.write(getIntegerInRange(position, "x", 0, WatchConstants.SCREEN_WIDTH - 1));
        os.write(getIntegerInRange(position, "y", 0, WatchConstants.SCREEN_HEIGHT - 1));
        JSONObject size = control.getJSONObject("size");
        os.write(getIntegerInRange(size, "width", 0, WatchConstants.SCREEN_WIDTH));
        os.write(getIntegerInRange(size, "height", 0, WatchConstants.SCREEN_HEIGHT));
        JSONObject style = control.optJSONObject("style");
        int flags = 0;
        String orientation = style.optString("orientation", "horizontal");
        switch (orientation) {
            case "vertical":
                flags |= 0x20;
                break;
            case "horizontal":
                break;
            default:
                throw new KnownParseError("Not supported orientation: " + orientation);
        }
        int border = 0;
        if (style != null) {
            border = style.optInt("border", 0);
        }
        os.write(flags); //RFU
        os.write(border);
        os.write(0); //RFU
        os.write(0); //RFU

        int dataPtr = screenContext.getAllocator().addBuffer(4);
        os.write((dataPtr >> 8) & 0xFF);
        os.write(dataPtr & 0xFF);

        int dataSize = buildNumberRangeFromMaxValue(maxValue);
        DataSourceResolutionContext info = new DataSourceResolutionContext(screenContext);
        info.dataSourceType = DataSourceType.NUMBER;
        info.dataRange = dataSize;

        JSONObject source = control.getJSONObject("source");
        os.write(compileSource(source, info));

        return os.toByteArray();
    }

    private int buildNumberRangeFromMaxValue(int maxValue) {
        int v = 0;
        while (maxValue > 0) {
            maxValue /= 256;
            v++;
        }
        return v << 5;
    }

    private byte[] compileImageControl(JSONObject control, ScreenContext screenContext) throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();

        os.write(WatchConstants.SCR_CONTROL_STATIC_IMAGE);
        JSONObject position = control.getJSONObject("position");
        os.write(getIntegerInRange(position, "x", 0, WatchConstants.SCREEN_WIDTH - 1));
        os.write(getIntegerInRange(position, "y", 0, WatchConstants.SCREEN_HEIGHT - 1));

        JSONObject style = control.optJSONObject("style");
        os.write(getIntegerInRange(style, "width", 0, WatchConstants.SCREEN_WIDTH));
        os.write(getIntegerInRange(style, "height", 0, WatchConstants.SCREEN_HEIGHT));

        JSONObject image = control.getJSONObject("image");
        writeResourceType(os, image);
        return os.toByteArray();
    }

    private byte[] compileImageFromSetControl(JSONObject control, ScreenContext screenContext) throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();

        os.write(WatchConstants.SCR_CONTROL_IMAGE_FROM_SET);
        JSONObject position = control.getJSONObject("position");
        os.write(getIntegerInRange(position, "x", 0, WatchConstants.SCREEN_WIDTH - 1));
        os.write(getIntegerInRange(position, "y", 0, WatchConstants.SCREEN_HEIGHT - 1));

        JSONObject style = control.getJSONObject("style");
        os.write(getIntegerInRange(style, "width", 0, WatchConstants.SCREEN_WIDTH));
        os.write(getIntegerInRange(style, "height", 0, WatchConstants.SCREEN_HEIGHT));

        JSONObject image = control.getJSONObject("imageSet");
        writeResourceType(os, image);

        int dataPtr = screenContext.getAllocator().addBuffer(4);
        os.write((dataPtr >> 8) & 0xFF);
        os.write(dataPtr & 0xFF);

        JSONObject source = control.getJSONObject("source");
        DataSourceResolutionContext info = new DataSourceResolutionContext(screenContext);
        info.dataSourceType = DataSourceType.NUMBER;
        info.dataRange = 0x40;
        os.write(compileSource(source, info));

        return os.toByteArray();
    }

    private void writeResourceType(ByteArrayOutputStream os, JSONObject image) throws JSONException {
        String imageType = image.getString("type");
        switch (imageType) {
            case "resource":
                String resourceId = image.getString("id");
                os.write(WatchConstants.RESOURCE_SOURCE_ATTACHED);
                os.write(resourceIdToNumber.get(resourceId));
                break;
            default:
                throw new KnownParseError("Invalid image type: " + imageType);

        }
    }

    private byte[] compileNumberControl(JSONObject control, ScreenContext screenContext) throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();

        os.write(WatchConstants.SCR_CONTROL_NUMBER);
        int numberRange = getIntegerNumberFormat(control.getString("numberRange"));
        os.write(numberRange);
        JSONObject position = control.getJSONObject("position");
        os.write(getIntegerInRange(position, "x", 0, WatchConstants.SCREEN_WIDTH - 1));
        os.write(getIntegerInRange(position, "y", 0, WatchConstants.SCREEN_HEIGHT - 1));

        JSONObject style = control.getJSONObject("style");
        int digitSpace = getIntegerInRange(style, "space", 0, 31);

        // do not allow left padding for ranges 0-1XXX
        boolean leftPadded = style.optBoolean("leftPadded", false) && (numberRange >> 4) % 2 != 0;

        switch (style.getString("type")) {
            case "generated":
                os.write((leftPadded ? 0x20 : 0) | digitSpace);
                os.write(getIntegerInRange(style, "thickness", 0, 63));
                os.write(getIntegerInRange(style, "width", 0, WatchConstants.SCREEN_WIDTH));
                os.write(getIntegerInRange(style, "height", 0, WatchConstants.SCREEN_HEIGHT));
                break;
            case "numbersFont":
                os.write(0x40 | (leftPadded ? 0x20 : 0) | digitSpace);
                os.write(0);
                JSONObject font = style.getJSONObject("numbersFont");
                writeResourceType(os, font);
                break;
        }
        int dataPtr = screenContext.getAllocator().addBuffer(4);
        os.write((dataPtr >> 8) & 0xFF);
        os.write(dataPtr & 0xFF);

        JSONObject source = control.getJSONObject("source");
        DataSourceResolutionContext info = new DataSourceResolutionContext(screenContext);
        info.dataSourceType = DataSourceType.NUMBER;
        info.dataRange = buildDataSourceRangeFromNumberRange(numberRange);
        os.write(compileSource(source, info));

        return os.toByteArray();
    }

    private int buildDataSourceRangeFromNumberRange(int range) {

        int size = 0;
        if (range == WatchConstants.NUMBER_RANGE_0__9 ||
                range == WatchConstants.NUMBER_RANGE_0__19 ||
                range == WatchConstants.NUMBER_RANGE_0__99 ||
                range == WatchConstants.NUMBER_RANGE_0__199 ||
                range == WatchConstants.NUMBER_RANGE_0__9_9 ||
                range == WatchConstants.NUMBER_RANGE_0__19_9) {
            size = 1;
        } else if (range == WatchConstants.NUMBER_RANGE_0__999 ||
                range == WatchConstants.NUMBER_RANGE_0__1999 ||
                range == WatchConstants.NUMBER_RANGE_0__9999 ||
                range == WatchConstants.NUMBER_RANGE_0__19999 ||
                range == WatchConstants.NUMBER_RANGE_0__99_9 ||
                range == WatchConstants.NUMBER_RANGE_0__199_9 ||
                range == WatchConstants.NUMBER_RANGE_0__999_9 ||
                range == WatchConstants.NUMBER_RANGE_0__1999_9 ||
                range == WatchConstants.NUMBER_RANGE_0__9_99 ||
                range == WatchConstants.NUMBER_RANGE_0__19_99 ||
                range == WatchConstants.NUMBER_RANGE_0__99_99 ||
                range == WatchConstants.NUMBER_RANGE_0__199_99) {
            size = 2;
        } else if (range == WatchConstants.NUMBER_RANGE_0__99999 ||
                range == WatchConstants.NUMBER_RANGE_0__9999_9 ||
                range == WatchConstants.NUMBER_RANGE_0__19999_9 ||
                range == WatchConstants.NUMBER_RANGE_0__99999_9 ||
                range == WatchConstants.NUMBER_RANGE_0__999_99 ||
                range == WatchConstants.NUMBER_RANGE_0__1999_99 ||
                range == WatchConstants.NUMBER_RANGE_0__9999_99 ||
                range == WatchConstants.NUMBER_RANGE_0__19999_99 ||
                range == WatchConstants.NUMBER_RANGE_0__99999_99) {
            size = 3;
        }
        return size << 5 | (range & 0xF);
    }

    private byte[] compileTextControl(JSONObject control, ScreenContext screenContext) throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();

        os.write(WatchConstants.SCR_CONTROL_TEXT);
        JSONObject position = control.getJSONObject("position");
        os.write(getIntegerInRange(position, "x", 0, WatchConstants.SCREEN_WIDTH - 1));
        os.write(getIntegerInRange(position, "y", 0, WatchConstants.SCREEN_HEIGHT - 1));
        JSONObject size = control.getJSONObject("size");
        os.write(getIntegerInRange(size, "width", 0, WatchConstants.SCREEN_WIDTH));
        os.write(getIntegerInRange(size, "height", 0, WatchConstants.SCREEN_HEIGHT));
        // write style
        int font = parseFont(control.getJSONObject("font"));
        os.write(font);
        int alignment = parseFontAlignment(control.getJSONObject("style"));
        alignment |= parseFontFlags(control.getJSONObject("style"));
        os.write(alignment);
        int flags = 0;//parseFontFlags(control.getJSONObject("style"));
        os.write(flags);
        os.write(0);

        int stringLength = control.optInt("length", WatchConstants.DEFAULT_TEXT_EXT_PROPERTY_LENGTH);
        if (stringLength < 0 || stringLength > WatchConstants.MAX_TEXT_EXT_PROPERTY_LENGTH)
            throw new KnownParseError("Text length is not in range [0, " +
                    WatchConstants.MAX_TEXT_EXT_PROPERTY_LENGTH + "]");

        DataSourceResolutionContext info = new DataSourceResolutionContext(screenContext);
        info.dataSourceType = DataSourceType.STRING;
        info.dataRange = stringLength;

        JSONObject source = control.getJSONObject("source");
        byte[] dataSourceData = compileSource(source, info);

        int dataPtr = screenContext.getAllocator().addBuffer(info.dataRange + 1);
        os.write((dataPtr >> 8) & 0xFF);
        os.write(dataPtr & 0xFF);

        os.write(dataSourceData);

        return os.toByteArray();
    }

    private int parseFontFlags(JSONObject style) {
        int flags = 0;
        Boolean multiline = style.optBoolean("multiline");
        flags = (multiline != null && multiline) ? WatchConstants.TEXT_FLAGS_MULTILINE : 0x0;
        return flags;
    }

    private int parseFontAlignment(JSONObject style) {
        int alignment = 0;
        String horizontalAlign = style.optString("horizontalAlign", "left");
        switch (horizontalAlign) {
            case "center":
                alignment |= WatchConstants.HORIZONTAL_ALIGN_CENTER;
                break;
            case "left":
                break;
            case "right":
                alignment |= WatchConstants.HORIZONTAL_ALIGN_RIGHT;
                break;
            default:
                throw new KnownParseError("Invalid horizontal align: " + horizontalAlign);
        }
        String verticalAlign = style.optString("verticalAlign", "top");
        switch (verticalAlign) {
            case "top":
                break;
            case "center":
                alignment |= WatchConstants.VERTICAL_ALIGN_CENTER;
                break;
            case "bottom":
                alignment |= WatchConstants.VERTICAL_ALIGN_BOTTOM;
                break;
            default:
                throw new KnownParseError("Invalid vertical align: " + verticalAlign);
        }
        return alignment;
    }

    private int parseFont(JSONObject font) throws JSONException {
        String fontType = font.getString("type");
        String fontName = font.getString("name");
        switch (fontType) {
            case "builtin":
                return buildBuiltinFontData(fontName);
        }
        throw new KnownParseError("Invalid font type: " + fontType);
    }

    private int buildBuiltinFontData(String fontName) {
        switch (fontName) {
            case "optionBig":
                return WatchConstants.FONT_NAME_OPTION_BIG;
            case "optionNormal":
                return WatchConstants.FONT_NAME_OPTION_NORMAL;
            case "smallRegular":
                return WatchConstants.FONT_NAME_SMALL_REGULAR;
            case "smallBold":
                return WatchConstants.FONT_NAME_SMALL_BOLD;
            case "normalRegular":
                return WatchConstants.FONT_NAME_NORMAL_REGULAR;
            case "normalBold":
                return WatchConstants.FONT_NAME_NORMAL_BOLD;
            case "bigRegular":
                return WatchConstants.FONT_NAME_BIG_REGULAR;
            case "bigBold":
                return WatchConstants.FONT_NAME_BIG_BOLD;
        }
        throw new KnownParseError("Invalid font name: " + fontName);
    }

    private int getIntegerNumberFormat(String numberRange) {
        switch (numberRange) {
            case "0-9":
                return WatchConstants.NUMBER_RANGE_0__9;
            case "0-19":
                return WatchConstants.NUMBER_RANGE_0__19;
            case "0-99":
                return WatchConstants.NUMBER_RANGE_0__99;
            case "0-199":
                return WatchConstants.NUMBER_RANGE_0__199;
            case "0-999":
                return WatchConstants.NUMBER_RANGE_0__999;
            case "0-1999":
                return WatchConstants.NUMBER_RANGE_0__1999;
            case "0-9999":
                return WatchConstants.NUMBER_RANGE_0__9999;
            case "0-19999":
                return WatchConstants.NUMBER_RANGE_0__19999;
            case "0-99999":
                return WatchConstants.NUMBER_RANGE_0__99999;
            case "0-9.9":
                return WatchConstants.NUMBER_RANGE_0__9_9;
            case "0-19.9":
                return WatchConstants.NUMBER_RANGE_0__19_9;
            case "0-99.9":
                return WatchConstants.NUMBER_RANGE_0__99_9;
            case "0-199.9":
                return WatchConstants.NUMBER_RANGE_0__199_9;
            case "0-999.9":
                return WatchConstants.NUMBER_RANGE_0__999_9;
            case "0-1999.9":
                return WatchConstants.NUMBER_RANGE_0__1999_9;
            case "0-9999.9":
                return WatchConstants.NUMBER_RANGE_0__9999_9;
            case "0-19999.9":
                return WatchConstants.NUMBER_RANGE_0__19999_9;
            case "0-99999.9":
                return WatchConstants.NUMBER_RANGE_0__99999_9;
            case "0-9.99":
                return WatchConstants.NUMBER_RANGE_0__9_99;
            case "0-19.99":
                return WatchConstants.NUMBER_RANGE_0__19_99;
            case "0-99.99":
                return WatchConstants.NUMBER_RANGE_0__99_99;
            case "0-199.99":
                return WatchConstants.NUMBER_RANGE_0__199_99;
            case "0-999.99":
                return WatchConstants.NUMBER_RANGE_0__999_99;
            case "0-1999.99":
                return WatchConstants.NUMBER_RANGE_0__1999_99;
            case "0-9999.99":
                return WatchConstants.NUMBER_RANGE_0__9999_99;
            case "0-19999.99":
                return WatchConstants.NUMBER_RANGE_0__19999_99;
            case "0-99999.99":
                return WatchConstants.NUMBER_RANGE_0__99999_99;
            default:
                throw new KnownParseError("Unknown number format: " + numberRange);
        }
    }

    private byte[] compileSource(JSONObject source, DataSourceResolutionContext info) throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        int flags = 0;
        Object converter = source.opt("converter");
        if (converter != null) {
            flags |= 0x80;
        }
        JSONObject index = source.optJSONObject("index");
        if (index != null) {
            flags |= 0x40;
        }
        switch (source.getString("type")) {
            case "static":
                os.write(flags | WatchConstants.DATA_SOURCE_STATIC);
                if (info.dataSourceType == DataSourceType.STRING) {
                    byte[] value = source.getString("value").getBytes();
                    os.write(value.length);
                    os.write(value, 0, value.length);
                    info.dataRange = value.length;
                } else if (info.dataSourceType == DataSourceType.NUMBER) {
                    int value;
                    if (info.resolver != null) {
                        value = (int) info.resolver.resolve(source.getString("value"));
                    } else {
                        value = source.getInt("value");
                    }
                    os.write(4);
                    os.write(value >> 24);
                    os.write(value >> 16 & 0xFF);
                    os.write(value >> 8 & 0xFF);
                    os.write(value & 0xFF);
                } else {
                    throw new KnownParseError("Static data source not supported for type: " + info.dataSourceType);
                }
                break;
            case "internal":
                os.write(flags | WatchConstants.DATA_SOURCE_INTERNAL);
                os.write(getInternalSourceKey(source.getString("property"), info.dataSourceType, info.dataRange));
                break;
            case "sensor":
                os.write(flags | WatchConstants.DATA_SOURCE_SENSOR);
                os.write(getSensorSourceKey(source.getString("property"), info.dataSourceType, info.dataRange));
                break;
            case "extension": {
                os.write(flags | WatchConstants.DATA_SOURCE_EXTERNAL);
                String extensionId = source.getString("extensionId");
                String property = source.getString("property");
                os.write(addExtensionProperty(new WatchExtensionProperty(extensionId, property, info.dataSourceType, info.dataRange)));
            }
            break;
            case "model": {
                os.write(flags | WatchConstants.DATA_SOURCE_WATCHSET_MODEL);
                String property = source.getString("property");
                FieldDefinition fieldDef = info.screenContext.getModel().get(property);
                if (fieldDef == null) {
                    throw new KnownParseError("Invalid model property: " + property);
                }
                info.resolver = fieldDef;
                os.write(fieldDef.getFieldId());
            }
            break;
            default:
                throw new KnownParseError("Unknown type: " + source.getString("type"));
        }

        if (index != null) {
            DataSourceResolutionContext idxCtx = new DataSourceResolutionContext(info.screenContext);
            idxCtx.dataSourceType = DataSourceType.NUMBER;
            idxCtx.dataRange = 0;
            os.write(compileSource(index, idxCtx));
        }
        if (converter != null) {
            parseConverters(os, converter);
        }
        return os.toByteArray();
    }

    private void parseConverters(ByteArrayOutputStream os, Object converter) throws Exception {
        if(converter instanceof String) {
            os.write(1);
            os.write(getConverterKey((String)converter));
        } else if (converter instanceof JSONArray) {
            JSONArray arr = (JSONArray)converter;
            os.write(arr.length());
            for(int i=0; i< arr.length(); i++) {
                os.write(getConverterKey(arr.getString(i)));
            }
        } else {
            throw new KnownParseError("Invalid converter");
        }
    }

    private int addExtensionProperty(WatchExtensionProperty property) {
        if (plugins.get(property.getPluginId()) == null) {
            throw new KnownParseError("Plugin is not available: " + property.getPluginId());
        }

        int paramIdx = extensionParameters.indexOf(property);
        if (paramIdx < 0) {
            extensionParameters.add(property);
            return extensionParameters.size() - 1;
        } else {
            WatchExtensionProperty oldProperty = extensionParameters.get(paramIdx);
            if (!oldProperty.getType().equals(property.getType())) {
                throw new KnownParseError("Property " + property.getPropertyId() + " is defined multiple times with different type");
            }
            oldProperty.setRange(mergePropertyRange(property.getType(), property.getRange(), oldProperty.getRange()));
            return paramIdx;
        }
    }

    private int mergePropertyRange(DataSourceType type, int range1, int range2) {
        if (DataSourceType.NUMBER == type) {
            return (Math.max(range1 >> 5, range2 >> 5) << 5) | Math.max(range1 & 0x1F, range2 & 0x1F);
        }
        return Math.max(range1, range2);
    }

    private int getInternalSourceKey(String property, DataSourceType dataSourceType, int dataSourceRange) {
        if (!DataSourceType.NUMBER.equals(dataSourceType)) {
            throw new IllegalArgumentException("Unknown data source type");
        }
        switch (property) {
            case "time":
                return WatchConstants.INTERNAL_DATA_SOURCE_TIME_IN_SECONDS;
            case "hour":
                return WatchConstants.INTERNAL_DATA_SOURCE_TIME_HOUR_24;
            case "hour12":
                return WatchConstants.INTERNAL_DATA_SOURCE_TIME_HOUR_12;
            case "hour12designator":
                return WatchConstants.INTERNAL_DATA_SOURCE_TIME_HOUR_12_DESIGNATOR;
            case "minutes":
                return WatchConstants.INTERNAL_DATA_SOURCE_TIME_MINUTES;
            case "seconds":
                return WatchConstants.INTERNAL_DATA_SOURCE_TIME_SECONDS;
            case "dayOfWeek":
                return WatchConstants.INTERNAL_DATA_SOURCE_DATE_DAY_OF_WEEK;
            case "dayOfMonth":
                return WatchConstants.INTERNAL_DATA_SOURCE_DATE_DAY_OF_MONTH;
            case "dayOfYear":
                return WatchConstants.INTERNAL_DATA_SOURCE_DATE_DAY_OF_YEAR;
            case "month":
                return WatchConstants.INTERNAL_DATA_SOURCE_DATE_MONTH;
            case "year":
                return WatchConstants.INTERNAL_DATA_SOURCE_DATE_YEAR;
            case "batteryLevel":
                return WatchConstants.INTERNAL_DATA_SOURCE_BATTERY_LEVEL;

            case "stopwatch.total.time":
                return WatchConstants.INTERNAL_DATA_SOURCE_STOPWATCH_TOTAL_TIME;
            case "stopwatch.currentLap.number":
                return WatchConstants.INTERNAL_DATA_SOURCE_STOPWATCH_CURRENT_LAP_NUMBER;
            case "stopwatch.currentLap.time":
                return WatchConstants.INTERNAL_DATA_SOURCE_STOPWATCH_CURRENT_LAP_TIME;
            case "stopwatch.currentLap.split":
                return WatchConstants.INTERNAL_DATA_SOURCE_STOPWATCH_CURRENT_LAP_SPLIT;
            case "stopwatch.recallLap.time":
                return WatchConstants.INTERNAL_DATA_SOURCE_STOPWATCH_RECALL_LAP_TIME;
            case "stopwatch.recallLap.split":
                return WatchConstants.INTERNAL_DATA_SOURCE_STOPWATCH_RECALL_LAP_SPLIT;
            case "stopwatch.lastLap.time":
                return WatchConstants.INTERNAL_DATA_SOURCE_STOPWATCH_LAST_LAP_TIME;
        }
        throw new KnownParseError("Unknown internal property: " + property);
    }

    private int getConverterKey(String converterName) {
        switch (converterName) {
            case "msToHours":
                return WatchConstants.CONVERTER_MS_TO_HOURS;
            case "msToMinutesRemainder":
                return WatchConstants.CONVERTER_MS_TO_MINUTES_REMAINDER;
            case "msToSecondsRemainder":
                return WatchConstants.CONVERTER_MS_TO_SECONDS_REMAINDER;
            case "msToCsRemainder":
                return WatchConstants.CONVERTER_MS_TO_CS_REMAINDER;
            case "msRemainder":
                return WatchConstants.CONVERTER_MS_REMAINDER;
            case "timeToHour24":
                return WatchConstants.CONVERTER_TIME_TO_HOUR_24;
            case "timeToRoundedHour24":
                return WatchConstants.CONVERTER_TIME_TO_ROUNDED_HOUR_24;
            case "timeToMinutes":
                return WatchConstants.CONVERTER_TIME_TO_MINUTES;
            case "timeToSeconds":
                return WatchConstants.CONVERTER_TIME_TO_SECONDS;
            case "timeToFiveMinutesRoundTime":
                return WatchConstants.CONVERTER_TIME_TO_FIVE_MINUTES_ROUNDED_TIME;
            case "hour24ToHour12":
                return WatchConstants.CONVERTER_HOUR_24_TO_HOUR_12;
            case "hour24ToHour12Period":
                return WatchConstants.CONVERTER_HOUR_24_TO_HOUR_12_PERIOD;
            case "minutesToPastToDesignator":
                return WatchConstants.CONVERTER_MINUTES_TO_PAST_TO_DESIGNATOR;
            case "minutesToPastToMinutes":
                return WatchConstants.CONVERTER_MINUTES_TO_PAST_TO_MINUTES;
        }
        throw new KnownParseError("Unknown converter: " + converterName);
    }

    private int getSensorSourceKey(String property, DataSourceType dataSourceType, int dataSourceRange) {
        if (!dataSourceType.equals(DataSourceType.NUMBER)) {
            throw new IllegalArgumentException("Unknown data source type");
        }
        switch (property) {
            case "heartRate":
                return WatchConstants.SENSOR_DATA_SOURCE_HR;
        }
        throw new KnownParseError("Unknown sensor property: " + property);
    }

    private int getIntegerInRange(JSONObject control, String property, int min, int max) throws JSONException {
        if (control == null || !control.has(property)) {
            return 0;
        }
        int val = control.getInt(property);
        if (val < min || val > max) {
            throw new KnownParseError("Value of " + property + " is not in range");
        }
        return val;
    }
}
