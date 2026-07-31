package com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;

import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.FreeformHintView.HintPhase;

public class FreeformHintViewController {

    private static final String TAG = "FreeformController";

    private static FreeformHintView sHintView;
    private static ViewGroup sDragLayer;

    /**
     * @param dragLayer       DragLayer instance
     * @param context         Launcher context
     * @param moduleResources Module's own Resources (for i18n string loading)
     * @param modulePackage   Module's package name
     */
    public static void init(ViewGroup dragLayer, Context context,
                             Resources moduleResources, String modulePackage) {
        // reuse attached view; stale after Activity recreation (deep sleep) → rebuild
        if (sHintView != null) {
            if (sHintView.getParent() != null && sHintView.isAttachedToWindow()) {
                Log.d(TAG, "init: reuse existing view, dragLayer=" + dragLayer);
                return;
            }
            Log.w(TAG, "init: stale view (parent=" + sHintView.getParent()
                    + " attached=" + sHintView.isAttachedToWindow() + ") — re-creating");
            sHintView = null;
        }
        sDragLayer = dragLayer;

        sHintView = new FreeformHintView(context, moduleResources, modulePackage);
        sHintView.setVisibility(ViewGroup.VISIBLE);

        // Create correct LayoutParams BEFORE addView to avoid InsettableFrameLayout crash
        ViewGroup.LayoutParams lp = createLayoutParams(dragLayer);
        sHintView.setLayoutParams(lp);

        dragLayer.addView(sHintView);
        sHintView.initLayout();
        Log.d(TAG, "init: created hint view on " + dragLayer);
    }

    /**
     * Ensure hint view is attached to the current DragLayer (self-heal after
     * deep sleep / removeAllViews). Fetches live DragLayer via Launcher.
     */
    public static void ensureWithLauncher(Context context,
                                           Resources moduleResources, String modulePackage) {
        ViewGroup dragLayer = findCurrentDragLayer(context);
        if (dragLayer == null) {
            Log.w(TAG, "ensureWithLauncher: no current DragLayer found");
            return;
        }
        if (sHintView != null && sHintView.getParent() == dragLayer
                && sHintView.isAttachedToWindow()) {
            return;
        }
        Log.w(TAG, "ensureWithLauncher: stale/missing view on current DragLayer — re-creating");
        sHintView = null;
        sDragLayer = null;
        init(dragLayer, context, moduleResources, modulePackage);
    }

    private static ViewGroup findCurrentDragLayer(Context context) {
        try {
            Class<?> launcherClass = Class.forName("com.android.launcher3.Launcher");
            Object launcher = launcherClass.getMethod("getLauncher", Context.class)
                    .invoke(null, context);
            if (launcher == null) return null;
            try {
                return (ViewGroup) launcherClass.getMethod("getDragLayer").invoke(launcher);
            } catch (Exception e1) {
                return (ViewGroup) launcherClass.getField("mDragLayer").get(launcher);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static ViewGroup.LayoutParams createLayoutParams(ViewGroup parent) {
        try {
            Class<?> lpClass = parent.getClass().getClassLoader().loadClass(
                    "com.android.launcher3.InsettableFrameLayout$LayoutParams");
            ViewGroup.LayoutParams lp = (ViewGroup.LayoutParams)
                    lpClass.getConstructor(int.class, int.class)
                            .newInstance(ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (lp instanceof android.widget.FrameLayout.LayoutParams) {
                ((android.widget.FrameLayout.LayoutParams) lp).gravity =
                        Gravity.TOP | Gravity.START;
            }
            return lp;
        } catch (Exception e) {
            android.widget.FrameLayout.LayoutParams lp =
                    new android.widget.FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            return lp;
        }
    }

    public static FreeformHintView getHintView() {
        return sHintView;
    }

    public static void setPhase(HintPhase phase) {
        if (sHintView != null) sHintView.setPhase(phase);
    }

    public static void setTaskBounds(Rect bounds) {
        if (sHintView != null) sHintView.setTaskBounds(bounds);
    }

    public static void setDisplayRotation(int rotation) {
        if (sHintView != null) sHintView.setDisplayRotation(rotation);
    }

    public static void reset() {
        if (sHintView != null) {
            sHintView.setPhase(HintPhase.HIDDEN);
        }
    }
}
