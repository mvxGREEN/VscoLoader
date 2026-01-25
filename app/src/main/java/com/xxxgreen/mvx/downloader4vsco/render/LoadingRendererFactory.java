package com.xxxgreen.mvx.downloader4vsco.render;

import android.content.Context;
import android.util.SparseArray;

import com.xxxgreen.mvx.downloader4vsco.render.animal.FishLoadingRenderer;
import com.xxxgreen.mvx.downloader4vsco.render.animal.GhostsEyeLoadingRenderer;
import com.xxxgreen.mvx.downloader4vsco.render.goods.BalloonLoadingRenderer;
import com.xxxgreen.mvx.downloader4vsco.render.goods.WaterBottleLoadingRenderer;
import com.xxxgreen.mvx.downloader4vsco.render.scenery.DayNightLoadingRenderer;
import com.xxxgreen.mvx.downloader4vsco.render.scenery.ElectricFanLoadingRenderer;
import com.xxxgreen.mvx.downloader4vsco.render.shapechange.CircleBroodLoadingRenderer;
import com.xxxgreen.mvx.downloader4vsco.render.shapechange.CoolWaitLoadingRenderer;

import java.lang.reflect.Constructor;

public final class LoadingRendererFactory {
    private static final SparseArray<Class<? extends LoadingRenderer>> LOADING_RENDERERS = new SparseArray<>();

    static {
        //scenery
        LOADING_RENDERERS.put(8, DayNightLoadingRenderer.class);
        LOADING_RENDERERS.put(9, ElectricFanLoadingRenderer.class);
        //animal
        LOADING_RENDERERS.put(10, FishLoadingRenderer.class);
        LOADING_RENDERERS.put(11, GhostsEyeLoadingRenderer.class);
        //goods
        LOADING_RENDERERS.put(12, BalloonLoadingRenderer.class);
        LOADING_RENDERERS.put(13, WaterBottleLoadingRenderer.class);
        //shape change
        LOADING_RENDERERS.put(14, CircleBroodLoadingRenderer.class);
        LOADING_RENDERERS.put(15, CoolWaitLoadingRenderer.class);
    }

    private LoadingRendererFactory() {
    }

    public static LoadingRenderer createLoadingRenderer(Context context, int loadingRendererId) throws Exception {
        Class<?> loadingRendererClazz = LOADING_RENDERERS.get(loadingRendererId);
        Constructor<?>[] constructors = loadingRendererClazz.getDeclaredConstructors();
        for (Constructor<?> constructor : constructors) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes != null
                    && parameterTypes.length == 1
                    && parameterTypes[0].equals(Context.class)) {
                constructor.setAccessible(true);
                return (LoadingRenderer) constructor.newInstance(context);
            }
        }

        throw new InstantiationException();
    }
}
