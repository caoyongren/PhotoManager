/*
 * Copyright (c) 2015 by k3b.
 *
 * This file is part of AndroFotoFinder.
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>
 */

package de.k3b.android.osmdroid;

import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;

import org.osmdroid.api.*;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

/**
 * An icon placed at a particular IGeoPoint on the map's surface.
 * Thanks to ResourceProxy the constructor can be called in a non-gui thread i.e. in AsyncTask.
 * <p>
 * Inspired by org.osmdroid.bonuspack.overlays.Marker.
 * <p>
 * Created by k3b on 16.07.2015.
 */
public class IconOverlay extends Overlay {
    /**
     * Usual values in the (U,V) coordinates system of the icon image
     */
    public static final float ANCHOR_CENTER = 0.5f, ANCHOR_LEFT = 0.0f, ANCHOR_TOP = 0.0f, ANCHOR_RIGHT = 1.0f, ANCHOR_BOTTOM = 1.0f;

    /*attributes for standard features:*/
    protected Drawable mIcon = null;
    protected IGeoPoint mPosition = null;

    protected float mBearing = 0.0f;
    protected float mAnchorU = ANCHOR_CENTER, mAnchorV = ANCHOR_CENTER;
    protected float mAlpha = 1.0f; //opaque

    protected boolean mFlat = false; //billboard;

    protected Point mPositionPixels = new Point();

    /**
     * save to be called in non-gui-thread
     */
    protected IconOverlay() {
        super();
    }

    /**
     * save to be called in non-gui-thread
     */
    public IconOverlay(IGeoPoint position, Drawable icon) {
        super();
        set(position, icon);
    }

    /**
     * Draw the icon.
     */
    @Override
    protected void draw(Canvas canvas, MapView mapView, boolean shadow) {
        if (shadow)
            return;
        if (mIcon == null)
            return;
        if (mPosition == null)
            return;

        final Projection pj = mapView.getProjection();

        pj.toPixels(mPosition, mPositionPixels);
        int width = mIcon.getIntrinsicWidth();
        int height = mIcon.getIntrinsicHeight();
        Rect rect = new Rect(0, 0, width, height);
        rect.offset(-(int) (mAnchorU * width), -(int) (mAnchorV * height));
        mIcon.setBounds(rect);

        mIcon.setAlpha((int) (mAlpha * 255));

        float rotationOnScreen = (mFlat ? -mBearing : mapView.getMapOrientation() - mBearing);
        drawAt(canvas, mIcon, mPositionPixels.x, mPositionPixels.y, false, rotationOnScreen);
    }

    public IGeoPoint getPosition() {
        return mPosition;
    }

    public IconOverlay set(IGeoPoint position, Drawable icon) {
        this.mPosition = position;
        this.mIcon = icon;
        return this;
    }

    public IconOverlay moveTo(final MotionEvent event, final MapView mapView) {
        final Projection pj = mapView.getProjection();
        moveTo(pj.fromPixels((int) event.getX(), (int) event.getY()), mapView);
        return this;
    }

    public IconOverlay moveTo(final IGeoPoint position, final MapView mapView) {
        mPosition = position;
        mapView.invalidate();
        return this;
    }
}
