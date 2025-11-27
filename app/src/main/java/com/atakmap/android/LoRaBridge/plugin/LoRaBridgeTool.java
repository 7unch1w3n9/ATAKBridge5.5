
package com.atakmap.android.LoRaBridge.plugin;

import com.atak.plugins.impl.AbstractPluginTool;
import com.atakmap.android.LoRaBridge.JNI.PluginNativeLoader;
import com.atakmap.android.LoRaBridge.LoRaBridgeDropDownReceiver;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.coremap.log.Log;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.ViewGroup;

import gov.tak.api.util.Disposable;

public class LoRaBridgeTool extends AbstractPluginTool implements Disposable {

    public LoRaBridgeTool(Context ctx) {
        super(ctx,
                ctx.getString(R.string.app_name),
                ctx.getString(R.string.app_name),
                ctx.getResources().getDrawable(R.drawable.ic_launcher),
                LoRaBridgeDropDownReceiver.SHOW_PLUGIN
        );
        PluginNativeLoader.init(ctx);
        try {
            PluginNativeLoader.loadAll();
        } catch (UnsatisfiedLinkError e) {
            Log.e("LoRaBridgeTool", "Failed to load native libraries", e);
            return;
        }
    }

    @Override
    public void dispose() {}
}

