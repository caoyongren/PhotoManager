/*
 * Copyright (c) 2015-2017 by k3b.
 *
 * This file is part of AndroFotoFinder / #APhotoManager.
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
 
package de.k3b.android.androFotoFinder.queries;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.CursorLoader;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import org.osmdroid.api.IGeoPoint;
import org.osmdroid.util.GeoPoint;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.k3b.FotoLibGlobal;
import de.k3b.android.androFotoFinder.Global;
import de.k3b.android.androFotoFinder.R;
import de.k3b.android.util.DBUtils;
import de.k3b.database.QueryParameter;
import de.k3b.database.SelectedFiles;
import de.k3b.database.SelectedItems;
import de.k3b.io.DirectoryFormatter;
import de.k3b.io.FileCommands;
import de.k3b.io.GalleryFilterParameter;
import de.k3b.io.GeoRectangle;
import de.k3b.io.IGalleryFilter;
import de.k3b.io.IGeoRectangle;

/**
 * contains all SQL needed to query the android gallery
 *
 * Created by k3b on 04.06.2015.
 */
public class FotoSql extends FotoSqlBase {

    public static final int SORT_BY_DATE_OLD = 1;
    public static final int SORT_BY_NAME_OLD = 2;
    public static final int SORT_BY_LOCATION_OLD = 3;
    public static final int SORT_BY_NAME_LEN_OLD = 4;

    public static final int SORT_BY_DATE = 'd';
    public static final int SORT_BY_NAME = 'n';
    public static final int SORT_BY_LOCATION = 'l';
    public static final int SORT_BY_NAME_LEN = 's'; // size

    public static final int SORT_BY_DEFAULT = SORT_BY_DATE;

    public static final int QUERY_TYPE_UNDEFINED = 0;
    public static final int QUERY_TYPE_GALLERY = 11;
    public static final int QUERY_TYPE_GROUP_DATE = 12;
    public static final int QUERY_TYPE_GROUP_ALBUM = 13;
    public static final int QUERY_TYPE_GROUP_PLACE = 14;
    public static final int QUERY_TYPE_GROUP_PLACE_MAP = 141;

    public static final int QUERY_TYPE_GROUP_COPY = 20;
    public static final int QUERY_TYPE_GROUP_MOVE = 21;

    public static final int QUERY_TYPE_GROUP_DEFAULT = QUERY_TYPE_GROUP_ALBUM;
    public static final int QUERY_TYPE_DEFAULT = QUERY_TYPE_GALLERY;

    // columns that must be avaulable in the Cursor
    public static final String SQL_COL_PK = MediaStore.Images.Media._ID;
    public static final String FILTER_COL_PK = SQL_COL_PK + "= ?";
    public static final String SQL_COL_DISPLAY_TEXT = "disp_txt";
    public static final String SQL_COL_LAT = MediaStore.Images.Media.LATITUDE;
    public static final String SQL_COL_LON = MediaStore.Images.Media.LONGITUDE;
    public static final String SQL_COL_SIZE = MediaStore.Images.Media.SIZE;

    // only works with api >= 16
    public static final String SQL_COL_MAX_WITH =
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN)
                ? "max(" + MediaStore.Images.Media.WIDTH + "," +
                                                MediaStore.Images.Media.HEIGHT +")"
                : "1024";


    public static final String SQL_COL_EXT_MEDIA_TYPE = MediaStore.Files.FileColumns.MEDIA_TYPE;
    public static final int MEDIA_TYPE_IMAGE = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE;    // 1
    public static final int MEDIA_TYPE_IMAGE_PRIVATE = 1000 + MEDIA_TYPE_IMAGE;                 // 1001 APhoto manager specific

    protected static final String FILTER_EXPR_PRIVATE
            = "(" + SQL_COL_EXT_MEDIA_TYPE + " = " + MEDIA_TYPE_IMAGE_PRIVATE + ")";
    protected static final String FILTER_EXPR_PRIVATE_PUBLIC
            = "(" + SQL_COL_EXT_MEDIA_TYPE + " in (" + MEDIA_TYPE_IMAGE_PRIVATE + "," + MEDIA_TYPE_IMAGE +"))";
    protected static final String FILTER_EXPR_PUBLIC
            = "(" + SQL_COL_EXT_MEDIA_TYPE + " = " + MEDIA_TYPE_IMAGE + ")";

    private static final String FILTER_EXPR_LAT_MAX = SQL_COL_LAT + " < ?";
    private static final String FILTER_EXPR_LAT_MIN = SQL_COL_LAT + " >= ?";
    private static final String FILTER_EXPR_NO_GPS = SQL_COL_LAT + " is null AND " + SQL_COL_LON + " is null";
    private static final String FILTER_EXPR_LON_MAX = SQL_COL_LON + " < ?";
    private static final String FILTER_EXPR_LON_MIN = SQL_COL_LON + " >= ?";
    public static final String SQL_COL_GPS = MediaStore.Images.Media.LONGITUDE;
    public static final String SQL_COL_COUNT = "count";
    public static final String SQL_COL_WHERE_PARAM = "where_param";

    public static final String SQL_COL_DATE_TAKEN = MediaStore.Images.Media.DATE_TAKEN;
    private static final String FILTER_EXPR_DATE_MAX = SQL_COL_DATE_TAKEN + " < ?";
    private static final String FILTER_EXPR_DATE_MIN = SQL_COL_DATE_TAKEN + " >= ?";
    public static final String SQL_COL_PATH = MediaStore.Images.Media.DATA;
    protected static final String FILTER_EXPR_PATH_LIKE = "(" + SQL_COL_PATH + " like ?)";

    // same format as dir. i.e. description='/2014/12/24/' or '/mnt/sdcard/pictures/'
    public static final String SQL_EXPR_DAY = "strftime('/%Y/%m/%d/', " + SQL_COL_DATE_TAKEN + " /1000, 'unixepoch', 'localtime')";

    public static final QueryParameter queryGroupByDate = new QueryParameter()
            .setID(QUERY_TYPE_GROUP_DATE)
            .addColumn(
                    "max(" + SQL_COL_PK + ") AS " + SQL_COL_PK,
                    SQL_EXPR_DAY + " AS " + SQL_COL_DISPLAY_TEXT,
                    "count(*) AS " + SQL_COL_COUNT,
                    "max(" + SQL_COL_GPS + ") AS " + SQL_COL_GPS,
                    "max(" + SQL_COL_PATH + ") AS " + SQL_COL_PATH)
            .addFrom(SQL_TABLE_EXTERNAL_CONTENT_URI_FILE_NAME)
            .addWhere(FILTER_EXPR_PRIVATE_PUBLIC)
            .addGroupBy(SQL_EXPR_DAY)
            .addOrderBy(SQL_EXPR_DAY);

    public static final String SQL_EXPR_FOLDER = "substr(" + SQL_COL_PATH + ",1,length(" + SQL_COL_PATH + ") - length(" + MediaStore.Images.Media.DISPLAY_NAME + "))";
    public static final QueryParameter queryGroupByDir = new QueryParameter()
            .setID(QUERY_TYPE_GROUP_ALBUM)
            .addColumn(
                    "max(" + SQL_COL_PK + ") AS " + SQL_COL_PK,
                    SQL_EXPR_FOLDER + " AS " + SQL_COL_DISPLAY_TEXT,
                    "count(*) AS " + SQL_COL_COUNT,
                    // (Substr(_data,1, length(_data) -  length(_display_Name)) = '/storage/sdcard0/DCIM/onw7b/2013/')
                    // "'(" + SQL_EXPR_FOLDER + " = ''' || " + SQL_EXPR_FOLDER + " || ''')'"
                    "max(" + SQL_COL_GPS + ") AS " + SQL_COL_GPS)
            .addFrom(SQL_TABLE_EXTERNAL_CONTENT_URI_FILE_NAME)
            .addWhere(FILTER_EXPR_PRIVATE_PUBLIC)
            .addGroupBy(SQL_EXPR_FOLDER)
            .addOrderBy(SQL_EXPR_FOLDER);

    /* image entries may become duplicated if media scanner finds new images that have not been inserted into media database yet
     * and aFotoSql tries to show the new image and triggers a filescan. */
    public static final QueryParameter queryGetDuplicates = new QueryParameter()
            .setID(QUERY_TYPE_UNDEFINED)
            .addColumn(
                    "min(" + SQL_COL_PK + ") AS " + SQL_COL_PK,
                    SQL_COL_PATH + " AS " + SQL_COL_DISPLAY_TEXT,
                    "count(*) AS " + SQL_COL_COUNT)
            .addFrom(SQL_TABLE_EXTERNAL_CONTENT_URI_FILE_NAME)
            .addWhere(SQL_COL_PATH + " IS NOT NULL ")
            .addGroupBy(SQL_COL_PATH)
            .addHaving("count(*) > 1")
            .addOrderBy(SQL_COL_PATH);

    /* image entries may not have DISPLAY_NAME which is essential for calculating the item-s folder. */
    public static final QueryParameter queryChangePath = new QueryParameter()
            .setID(QUERY_TYPE_UNDEFINED)
            .addColumn(
                    SQL_COL_PK,
                    SQL_COL_PATH,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.TITLE)
            .addFrom(SQL_TABLE_EXTERNAL_CONTENT_URI_FILE_NAME)
            ;

    /* image entries may not have DISPLAY_NAME which is essential for calculating the item-s folder. */
    public static final QueryParameter queryGetMissingDisplayNames = new QueryParameter(queryChangePath)
            .addWhere(MediaStore.MediaColumns.DISPLAY_NAME + " is null")
            .addWhere(FILTER_EXPR_PRIVATE_PUBLIC)
            ;

    // the bigger the smaller the area
    private static final double GROUPFACTOR_FOR_Z0 = 0.025;

    /** to avoid cascade delete of linked file when mediaDB-item is deleted
     *  the links are first set to null before delete. */
    private static final String DELETED_FILE_MARKER = null;

    public static final double getGroupFactor(final int _zoomLevel) {
        int zoomLevel = _zoomLevel;
        double result = GROUPFACTOR_FOR_Z0;
        while (zoomLevel > 0) {
            // result <<= 2; //
            result = result * 2;
            zoomLevel--;
        }

        if (Global.debugEnabled) {
            Log.e(Global.LOG_CONTEXT, "FotoSql.getGroupFactor(" + _zoomLevel + ") => " + result);
        }

        return result;
    }

    public static final QueryParameter queryGroupByPlace = getQueryGroupByPlace(100);

    public static QueryParameter getQueryGroupByPlace(double groupingFactor) {
        //String SQL_EXPR_LAT = "(round(" + SQL_COL_LAT + " - 0.00499, 2))";
        //String SQL_EXPR_LON = "(round(" + SQL_COL_LON + " - 0.00499, 2))";

        // "- 0.5" else rounding "10.6" becomes 11.0
        // + (1/groupingFactor/2) in the middle of grouping area
        String SQL_EXPR_LAT = "((round((" + SQL_COL_LAT + " * " + groupingFactor + ") - 0.5) /"
                + groupingFactor + ") + " + (1/groupingFactor/2) + ")";
        String SQL_EXPR_LON = "((round((" + SQL_COL_LON + " * " + groupingFactor + ") - 0.5) /"
                + groupingFactor + ") + " + (1/groupingFactor/2) + ")";

        QueryParameter result = new QueryParameter();

        result.setID(QUERY_TYPE_GROUP_PLACE)
                .addColumn(
                        "max(" + SQL_COL_PK + ") AS " + SQL_COL_PK,
                        SQL_EXPR_LAT + " AS " + SQL_COL_LAT,
                        SQL_EXPR_LON + " AS " + SQL_COL_LON,
                        "count(*) AS " + SQL_COL_COUNT)
                .addFrom(SQL_TABLE_EXTERNAL_CONTENT_URI_FILE_NAME)
                .addWhere(FILTER_EXPR_PRIVATE_PUBLIC)
                .addGroupBy(SQL_EXPR_LAT, SQL_EXPR_LON)
                .addOrderBy(SQL_EXPR_LAT, SQL_EXPR_LON);

        return result;
    }

    public static final String[] DEFAULT_GALLERY_COLUMNS = new String[]{SQL_COL_PK,
            SQL_COL_PATH + " AS " + SQL_COL_DISPLAY_TEXT,
            // "0 AS " + SQL_COL_COUNT,
            SQL_COL_MAX_WITH + " AS " + SQL_COL_SIZE,
            SQL_COL_GPS,
            SQL_COL_PATH};

    public static final QueryParameter queryDetail = new QueryParameter()
            .setID(QUERY_TYPE_GALLERY)
            .addColumn(
                    DEFAULT_GALLERY_COLUMNS)
            .addFrom(SQL_TABLE_EXTERNAL_CONTENT_URI_FILE_NAME)
            ;

    public static final QueryParameter queryGps = new QueryParameter()
            .setID(QUERY_TYPE_UNDEFINED)
            .addColumn(
                    SQL_COL_PK,
                    // SQL_COL_PATH + " AS " + SQL_COL_DISPLAY_TEXT,
                    // "0 AS " + SQL_COL_COUNT,
                    SQL_COL_LAT, SQL_COL_LON)
            .addFrom(SQL_TABLE_EXTERNAL_CONTENT_URI_FILE_NAME)
            ;

    public static void filter2Query(QueryParameter resultQuery, IGalleryFilter filter, boolean clearWhereBefore) {
        if ((resultQuery != null) && (!GalleryFilterParameter.isEmpty(filter))) {
            if (clearWhereBefore) {
                resultQuery.clearWhere();
            }

            if (filter.isNonGeoOnly()) {
                resultQuery.addWhere(FILTER_EXPR_NO_GPS);
            } else {
                addWhereFilterLatLon(resultQuery, filter);
            }

            if (filter.getDateMin() != 0) resultQuery.addWhere(FILTER_EXPR_DATE_MIN, Long.toString(filter.getDateMin()));
            if (filter.getDateMax() != 0) resultQuery.addWhere(FILTER_EXPR_DATE_MAX, Long.toString(filter.getDateMax()));

            String path = filter.getPath();
            if ((path != null) && (path.length() > 0)) resultQuery.addWhere(FILTER_EXPR_PATH_LIKE, path);
        }
    }

    /** translates a query back to filter */
    public static IGalleryFilter parseQuery(QueryParameter query, boolean remove) {
        if (query != null) {
            GalleryFilterParameter filter = new GalleryFilterParameter();
            if (null != getParams(query, FILTER_EXPR_NO_GPS, remove)) {
                filter.setNonGeoOnly(true);
            } else {
                filter.setLogitude(getParam(query, FILTER_EXPR_LON_MIN, remove), getParam(query, FILTER_EXPR_LON_MAX, remove));
                filter.setLatitude(getParam(query, FILTER_EXPR_LAT_MIN, remove), getParam(query, FILTER_EXPR_LAT_MAX, remove));
            }

	        filter.setDate(getParam(query, FILTER_EXPR_DATE_MIN, remove), getParam(query, FILTER_EXPR_DATE_MAX, remove));
            filter.setPath(getParam(query, FILTER_EXPR_PATH_LIKE, remove));

            return filter;
        }
        return null;
    }

    /** @return return param for expression inside query. null if expression is not in query or number of params is not 1. */
    protected static String getParam(QueryParameter query, String expresion, boolean remove) {
        final String[] result = getParams(query, expresion, remove);
        return ((result != null) && (result.length > 0)) ? result[0] : null;
    }

    /** @return return all params for expression inside query. null if expression is not in query */
    protected static String[] getParams(QueryParameter query, String expresion, boolean remove) {
        return query.getWhereParameter(expresion, remove);
    }

    public static QueryParameter setWhereSelectionPks(QueryParameter query, SelectedItems selectedItems) {
        if ((query != null) && (selectedItems != null) && (!selectedItems.isEmpty())) {
            String pksAsListString = selectedItems.toString();
            setWhereSelectionPks(query, pksAsListString);
        }
        return query;
    }

    public static QueryParameter setWhereSelectionPks(QueryParameter query, String pksAsListString) {
        if ((pksAsListString != null) && (pksAsListString.length() > 0)) {
            query.clearWhere()
                    .addWhere(FotoSql.SQL_COL_PK + " in (" + pksAsListString + ")")
            ;
        }
        return query;
    }

    public static void setWhereSelectionPaths(QueryParameter query, SelectedFiles selectedItems) {
        if ((query != null) && (selectedItems != null) && (selectedItems.size() > 0)) {
            query.clearWhere()
                    .addWhere(FotoSql.SQL_COL_PATH + " in (" + selectedItems.toString() + ")")
            ;
        }
    }

    public static void setWhereFileNames(QueryParameter query, String... fileNames) {
        if ((query != null) && (fileNames != null) && (fileNames.length > 0)) {
            query.clearWhere()
                    .addWhere(getWhereInFileNames(fileNames))
            ;
        }
    }

    public static void addWhereLatLonNotNull(QueryParameter query) {
        query.addWhere(FotoSql.SQL_COL_LAT + " is not null and " + FotoSql.SQL_COL_LON + " is not null")
        ;
    }

    public static void addWhereFilterLatLon(QueryParameter parameters, IGeoRectangle filter) {
        if ((parameters != null) && (filter != null)) {
            addWhereFilterLatLon(parameters, filter.getLatitudeMin(),
                    filter.getLatitudeMax(), filter.getLogituedMin(), filter.getLogituedMax());
        }
    }

    public static void addWhereFilterLatLon(QueryParameter query, double latitudeMin, double latitudeMax, double logituedMin, double logituedMax) {
        if (!Double.isNaN(latitudeMin)) query.addWhere(FILTER_EXPR_LAT_MIN, DirectoryFormatter.parseLatLon(latitudeMin));
        if (!Double.isNaN(latitudeMax)) query.addWhere(FILTER_EXPR_LAT_MAX, DirectoryFormatter.parseLatLon(latitudeMax));
        if (!Double.isNaN(logituedMin)) query.addWhere(FILTER_EXPR_LON_MIN, DirectoryFormatter.parseLatLon(logituedMin));
        if (!Double.isNaN(logituedMax)) query.addWhere(FILTER_EXPR_LON_MAX, DirectoryFormatter.parseLatLon(logituedMax));
    }

    public static void addPathWhere(QueryParameter newQuery, String selectedAbsolutePath, int dirQueryID) {
        if ((selectedAbsolutePath != null) && (selectedAbsolutePath.length() > 0)) {
            if (QUERY_TYPE_GROUP_DATE == dirQueryID) {
                addWhereDatePath(newQuery, selectedAbsolutePath);
            } else {
                // selectedAbsolutePath is assumed to be a file path i.e. /mnt/sdcard/pictures/
                addWhereDirectoryPath(newQuery, selectedAbsolutePath);
            }
        }
    }

    /**
     * directory path i.e. /mnt/sdcard/pictures/
     */
    private static void addWhereDirectoryPath(QueryParameter newQuery, String selectedAbsolutePath) {
        if (FotoViewerParameter.includeSubItems) {
            newQuery
                    .addWhere(FotoSql.SQL_COL_PATH + " like ?", selectedAbsolutePath + "%")
                            // .addWhere(FotoSql.SQL_COL_PATH + " like '" + selectedAbsolutePath + "%'")
                    .addOrderBy(FotoSql.SQL_COL_PATH);
        } else {
            // foldername exact match
            newQuery
                    .addWhere(SQL_EXPR_FOLDER + " =  ?", selectedAbsolutePath)
                    .addOrderBy(FotoSql.SQL_COL_PATH);
        }
    }

    /**
     * path has format /year/month/day/ or /year/month/ or /year/ or /
     */
    private static void addWhereDatePath(QueryParameter newQuery, String selectedAbsolutePath) {
        Date from = new Date();
        Date to = new Date();

        DirectoryFormatter.getDates(selectedAbsolutePath, from, to);

        if (to.getTime() == 0) {
            newQuery
                    .addWhere(SQL_COL_DATE_TAKEN + " in (0,-1, null)")
                    .addOrderBy(SQL_COL_DATE_TAKEN + " desc");
        } else {
            newQuery
                    .addWhere(FILTER_EXPR_DATE_MIN, "" + from.getTime())
                    .addWhere(FILTER_EXPR_DATE_MAX, "" + to.getTime())
                    .addOrderBy(SQL_COL_DATE_TAKEN + " desc");
        }
    }

    public static QueryParameter getQuery(int queryID) {
        switch (queryID) {
            case QUERY_TYPE_UNDEFINED:
                return null;
            case QUERY_TYPE_GALLERY:
                return queryDetail;
            case QUERY_TYPE_GROUP_DATE:
                return queryGroupByDate;
            case QUERY_TYPE_GROUP_ALBUM:
                return queryGroupByDir;
            case QUERY_TYPE_GROUP_PLACE:
            case QUERY_TYPE_GROUP_PLACE_MAP:
                return queryGroupByPlace;
            case QUERY_TYPE_GROUP_COPY:
            case QUERY_TYPE_GROUP_MOVE:
                return null;
            default:
                Log.e(Global.LOG_CONTEXT, "FotoSql.getQuery(" + queryID + "): unknown ID");
                return null;
        }
    }

    public static String getName(Context context, int id) {
        switch (id) {
            case IGalleryFilter.SORT_BY_NONE_OLD:
            case IGalleryFilter.SORT_BY_NONE:
                return context.getString(R.string.sort_by_none);
            case SORT_BY_DATE_OLD:
            case SORT_BY_DATE:
                return context.getString(R.string.sort_by_date);
            case SORT_BY_NAME_OLD:
            case SORT_BY_NAME:
                return context.getString(R.string.sort_by_name);
            case SORT_BY_LOCATION_OLD:
            case SORT_BY_LOCATION:
                return context.getString(R.string.sort_by_place);
            case SORT_BY_NAME_LEN_OLD:
            case SORT_BY_NAME_LEN:
                return context.getString(R.string.sort_by_name_len);

            case QUERY_TYPE_GALLERY:
                return context.getString(R.string.gallery_title);
            case QUERY_TYPE_GROUP_DATE:
                return context.getString(R.string.sort_by_date);
            case QUERY_TYPE_GROUP_ALBUM:
                return context.getString(R.string.sort_by_folder);
            case QUERY_TYPE_GROUP_PLACE:
            case QUERY_TYPE_GROUP_PLACE_MAP:
                return context.getString(R.string.sort_by_place);
            case QUERY_TYPE_GROUP_COPY:
                return context.getString(R.string.destination_copy);
            case QUERY_TYPE_GROUP_MOVE:
                return context.getString(R.string.destination_move);
            default:
                return "???";
        }

    }

    public static QueryParameter setSort(QueryParameter result, int sortID, boolean ascending) {
        String asc = (ascending) ? " asc" : " desc";
        result.replaceOrderBy();
        switch (sortID) {
            case SORT_BY_DATE_OLD:
            case SORT_BY_DATE:
                return result.replaceOrderBy(SQL_COL_DATE_TAKEN + asc);
            case SORT_BY_NAME_OLD:
            case SORT_BY_NAME:
                return result.replaceOrderBy(SQL_COL_PATH + asc);
            case SORT_BY_LOCATION_OLD:
            case SORT_BY_LOCATION:
                return result.replaceOrderBy(SQL_COL_GPS + asc, MediaStore.Images.Media.LATITUDE + asc);
            case SORT_BY_NAME_LEN_OLD:
            case SORT_BY_NAME_LEN:
                return result.replaceOrderBy("length(" + SQL_COL_PATH + ")"+asc);
            default: return  result;
        }
    }

    public static boolean set(GalleryFilterParameter dest, String selectedAbsolutePath, int queryTypeId) {
        switch (queryTypeId) {
            case FotoSql.QUERY_TYPE_GROUP_ALBUM:
                dest.setPath(selectedAbsolutePath + "%");
                return true;
            case FotoSql.QUERY_TYPE_GROUP_DATE:
                Date from = new Date();
                Date to = new Date();

                DirectoryFormatter.getDates(selectedAbsolutePath, from, to);
                dest.setDateMin(from.getTime());
                dest.setDateMax(to.getTime());
                return true;
            case FotoSql.QUERY_TYPE_GROUP_PLACE_MAP:
            case FotoSql.QUERY_TYPE_GROUP_PLACE:
                IGeoRectangle geo = DirectoryFormatter.parseLatLon(selectedAbsolutePath);
                if (geo != null) {
                    dest.get(geo);
                }
                return true;
            default:break;
        }
        return false;
    }

	/** converts content-Uri-with-id to full path */
    public static String execGetFotoPath(Context context, Uri uriWithID) {
        Cursor c = null;
        try {
            c = createCursorForQuery("execGetFotoPath", context, uriWithID.toString(), null, null, null, FotoSql.SQL_COL_PATH);
            if (c.moveToFirst()) {
                return DBUtils.getString(c,FotoSql.SQL_COL_PATH, null);
            }
        } catch (Exception ex) {
            Log.e(Global.LOG_CONTEXT, "FotoSql.execGetFotoPath() Cannot get path from " + uriWithID, ex);
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

	/** search for all full-image-file-paths that matches pathfilter  */
    public static List<String> execGetFotoPaths(Context context, String pathFilter) {
        ArrayList<String> result = new ArrayList<String>();

        Cursor c = null;
        try {
            c = createCursorForQuery("execGetFotoPaths", context,SQL_TABLE_EXTERNAL_CONTENT_URI_FILE_NAME,
                        FotoSql.SQL_COL_PATH + " like ? and " + FILTER_EXPR_PRIVATE_PUBLIC,
                        new String[]{pathFilter}, FotoSql.SQL_COL_PATH, FotoSql.SQL_COL_PATH);
            while (c.moveToNext()) {
                result.add(c.getString(0));
            }
        } catch (Exception ex) {
            Log.e(Global.LOG_CONTEXT, "FotoSql.execGetFotoPaths() Cannot get path from: " + FotoSql.SQL_COL_PATH + " like '" + pathFilter +"'", ex);
        } finally {
            if (c != null) c.close();
        }

        if (Global.debugEnabled) {
            Log.d(Global.LOG_CONTEXT, "FotoSql.execGetFotoPaths() result count=" + result.size());
        }
        return result;
    }

    /**
     * Write geo data (lat/lon) media database.<br/>
     */
    public static int execUpdateGeo(final Context context, double latitude, double longitude, SelectedFiles selectedItems) {
        QueryParameter where = new QueryParameter();
        setWhereSelectionPaths(where, selectedItems);
		setWhereVisibility(where, IGalleryFilter.VISIBILITY_PRIVATE_PUBLIC);

        ContentValues values = new ContentValues(2);
        values.put(SQL_COL_LAT, DirectoryFormatter.parseLatLon(latitude));
        values.put(SQL_COL_LON, DirectoryFormatter.parseLatLon(longitude));
        ContentResolver resolver = context.getContentResolver();

        return exexUpdateImpl("execUpdateGeo", context, values, where.toAndroidWhere(), where.toAndroidParameters());
    }
	
    public static Cursor createCursorForQuery(String dbgContext, final Context context, QueryParameter parameters, int visibility) {
        setWhereVisibility(parameters, visibility);
        return createCursorForQuery(dbgContext, context, parameters.toFrom(), parameters.toAndroidWhere(),
                parameters.toAndroidParameters(), parameters.toOrderBy(),
                parameters.toColumns()
        );
    }

    /** every cursor query should go through this. adds logging if enabled */
    private static Cursor createCursorForQuery(String dbgContext, final Context context, final String from, final String sqlWhereStatement,
                                               final String[] sqlWhereParameters, final String sqlSortOrder,
                                               final String... sqlSelectColums) {
        ContentResolver resolver = context.getContentResolver();
        Cursor query = resolver.query(Uri.parse(from), sqlSelectColums, sqlWhereStatement, sqlWhereParameters, sqlSortOrder);
        if (Global.debugEnabledSql) {
            Log.i(Global.LOG_CONTEXT, dbgContext +": FotoSql.createCursorForQuery:\n" +
                    QueryParameter.toString(sqlSelectColums, null, from, sqlWhereStatement,
                            sqlWhereParameters, sqlSortOrder, query.getCount()));
        }

        return query;
    }

    public static IGeoRectangle execGetGeoRectangle(Context context, IGalleryFilter filter, SelectedItems selectedItems) {
        QueryParameter query = new QueryParameter()
                .setID(QUERY_TYPE_UNDEFINED)
                .addColumn(
                        "min(" + SQL_COL_LAT + ") AS LAT_MIN",
                        "max(" + SQL_COL_LAT + ") AS LAT_MAX",
                        "min(" + SQL_COL_LON + ") AS LON_MIN",
                        "max(" + SQL_COL_LON + ") AS LON_MAX",
                        "count(*)"
                )
                .addFrom(SQL_TABLE_EXTERNAL_CONTENT_URI_FILE_NAME)
                ;

        if (!GalleryFilterParameter.isEmpty(filter)) {
            filter2Query(query, filter, true);
        }

        if (selectedItems != null) {
            setWhereSelectionPks(query, selectedItems);
        }
        FotoSql.addWhereLatLonNotNull(query);

        Cursor c = null;
        try {
            c = createCursorForQuery("execGetGeoRectangle", context, query, IGalleryFilter.VISIBILITY_PRIVATE_PUBLIC);
            if (c.moveToFirst()) {
                GeoRectangle result = new GeoRectangle();
                result.setLatitude(c.getDouble(0), c.getDouble(1));
                result.setLogitude(c.getDouble(2), c.getDouble(3));

                if (Global.debugEnabledSql) {
                    Log.i(Global.LOG_CONTEXT, "FotoSql.execGetGeoRectangle() => " + result + " from " + c.getLong(4) + " via\n\t" + query);
                }

                return result;
            }
        } catch (Exception ex) {
            Log.e(Global.LOG_CONTEXT, "FotoSql.execGetGeoRectangle(): error executing " + query, ex);
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    /** gets IGeoPoint either from file if fullPath is not null else from db via id */
    public static IGeoPoint execGetPosition(Context context, String fullPath, long id) {
        QueryParameter query = new QueryParameter()
        .setID(QUERY_TYPE_UNDEFINED)
                .addColumn(SQL_COL_LAT, SQL_COL_LON)
                .addFrom(SQL_TABLE_EXTERNAL_CONTENT_URI_FILE_NAME)
                .addWhere(SQL_COL_LAT + " IS NOT NULL")
                .addWhere(SQL_COL_LON + " IS NOT NULL");

        if (fullPath != null) {
            query.addWhere(SQL_COL_PATH + "= ?", fullPath);

        } else {
            query.addWhere(FILTER_COL_PK, "" + id);
        }

        Cursor c = null;
        try {
            c = createCursorForQuery("execGetPosition", context, query, IGalleryFilter.VISIBILITY_PRIVATE_PUBLIC);
            if (c.moveToFirst()) {
                GeoPoint result = new GeoPoint(c.getDouble(0),c.getDouble(1));
                return result;
            }
        } catch (Exception ex) {
            Log.e(Global.LOG_CONTEXT, "FotoSql.execGetPosition: error executing " + query, ex);
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    /**
     * @return returns a hashmap filename => mediaID
     */
    public static Map<String, Long> execGetPathIdMap(Context context, String... fileNames) {
        Map<String, Long> result = new HashMap<String, Long>();

        String whereFileNames = getWhereInFileNames(fileNames);
        if (whereFileNames != null) {
            QueryParameter query = new QueryParameter()
                    .setID(QUERY_TYPE_UNDEFINED)
                    .addColumn(SQL_COL_PK, SQL_COL_PATH)
                    .addFrom(SQL_TABLE_EXTERNAL_CONTENT_URI_FILE_NAME)
                    .addWhere(whereFileNames);

            Cursor c = null;
            try {
                c = createCursorForQuery("execGetPathIdMap", context, query, IGalleryFilter.VISIBILITY_PRIVATE_PUBLIC);
                while (c.moveToNext()) {
                    result.put(c.getString(1),c.getLong(0));
                }
            } catch (Exception ex) {
                Log.e(Global.LOG_CONTEXT, "FotoSql.execGetPathIdMap: error executing " + query, ex);
            } finally {
                if (c != null) c.close();
            }
        }
        return result;
    }

    public static String getWhereInFileNames(String... fileNames) {
        if (fileNames != null) {
            StringBuilder filter = new StringBuilder();
            filter.append(SQL_COL_PATH).append(" in (");

            int count = 0;
            for (String fileName : fileNames) {
                if ((fileName != null) &&!FileCommands.isSidecar(fileName)) {
                    if (count > 0) filter.append(", ");
                    filter.append("'").append(fileName).append("'");
                    count++;
                }
            }

            filter.append(")");

            if (count > 0) return filter.toString();
        }
        return null;
    }

    public static int execUpdate(String dbgContext, Context context, long id, ContentValues values) {
        return exexUpdateImpl(dbgContext, context, values, FILTER_COL_PK, new String[]{Long.toString(id)});
    }

    public static int execUpdate(String dbgContext, Context context, String path, ContentValues values, int visibility) {
        return exexUpdateImpl(dbgContext, context, values, getFilterExprPathLikeWithVisibility(visibility), new String[]{path});
    }

    /** every database update should go through this. adds logging if enabled */
    protected static int exexUpdateImpl(String dbgContext, Context context, ContentValues values, String sqlWhere, String[] selectionArgs) {
        int result = context.getContentResolver().update(SQL_TABLE_EXTERNAL_CONTENT_URI_FILE,
                    values, sqlWhere,
                    selectionArgs);
        if (Global.debugEnabledSql) {
            Log.i(Global.LOG_CONTEXT, dbgContext + ":FotoSql.exexUpdate\n" +
                    QueryParameter.toString(null, values.toString(), SQL_TABLE_EXTERNAL_CONTENT_URI_FILE_NAME,
                    sqlWhere, selectionArgs, null, result));
        }
        return result;
    }

    protected static String getFilterExprPathLikeWithVisibility(int visibility) {
        // visibility IGalleryFilter.VISIBILITY_PRIVATE_PUBLIC
        return FotoSql.FILTER_EXPR_PATH_LIKE + " AND " + getFilterExpressionVisibility(visibility);
    }

    /** every database insert should go through this. adds logging if enabled */
    public static Uri execInsert(String dbgContext, Context context, ContentValues values) {
        Uri result = context.getContentResolver().insert(SQL_TABLE_EXTERNAL_CONTENT_URI, values);
        if (Global.debugEnabledSql) {
            Log.i(Global.LOG_CONTEXT, dbgContext + ":FotoSql.execInsert" +
                    values.toString() + " => " + result);
        }
        return result;
    }

    @NonNull
    public static CursorLoader createCursorLoader(Context context, final QueryParameter query) {
        FotoSql.setWhereVisibility(query, IGalleryFilter.VISIBILITY_DEFAULT);
        final CursorLoader loader = new CursorLoaderWithException(context, query);
        return loader;
    }

    public static int execDeleteByPath(String dbgContext, Activity context, String parentDirString, int visibility) {
        int delCount = FotoSql.deleteMedia(dbgContext, context, getFilterExprPathLikeWithVisibility(visibility), new String[] {parentDirString + "/%"}, true);
        return delCount;
    }

    /**
     * Deletes media items specified by where with the option to prevent cascade delete of the image.
     */
    public static int deleteMedia(String dbgContext, Context context, String where, String[] selectionArgs, boolean preventDeleteImageFile)
    {
        String[] lastSelectionArgs = selectionArgs;
        String lastUsedWhereClause = where;
        int delCount = 0;
        try {
            if (preventDeleteImageFile) {
                // set SQL_COL_PATH empty so sql-delete cannot cascade delete the referenced image-file via delete trigger
                ContentValues values = new ContentValues();
                values.put(FotoSql.SQL_COL_PATH, DELETED_FILE_MARKER);
                values.put(FotoSql.SQL_COL_EXT_MEDIA_TYPE, 0); // so it will not be shown as image any more
                exexUpdateImpl(dbgContext + "-a: FotoSql.deleteMedia: ",
                        context, values, lastUsedWhereClause, lastSelectionArgs);

                lastUsedWhereClause = FotoSql.SQL_COL_PATH + " is null";
                lastSelectionArgs = null;
                delCount = context.getContentResolver()
                        .delete(SQL_TABLE_EXTERNAL_CONTENT_URI_FILE, lastUsedWhereClause, lastSelectionArgs);
                if (Global.debugEnabledSql) {
                    Log.i(Global.LOG_CONTEXT, dbgContext + "-b: FotoSql.deleteMedia delete\n" +
                            QueryParameter.toString(null, null, SQL_TABLE_EXTERNAL_CONTENT_URI_FILE_NAME,
                            lastUsedWhereClause, lastSelectionArgs, null, delCount));
                }
            } else {
                delCount = context.getContentResolver()
                        .delete(SQL_TABLE_EXTERNAL_CONTENT_URI_FILE, lastUsedWhereClause, lastSelectionArgs);
                if (Global.debugEnabledSql) {
                    Log.i(Global.LOG_CONTEXT, dbgContext +": FotoSql.deleteMedia\ndelete " +
                            QueryParameter.toString(null, null,
                                    SQL_TABLE_EXTERNAL_CONTENT_URI_FILE_NAME,
                                    lastUsedWhereClause, lastSelectionArgs, null, delCount));
                }
            }
        } catch (Exception ex) {
            // null pointer exception when delete matches not items??
            final String msg = dbgContext + ": Exception in FotoSql.deleteMedia:\n" +
                    QueryParameter.toString(null, null, SQL_TABLE_EXTERNAL_CONTENT_URI_FILE_NAME,
                    lastUsedWhereClause, lastSelectionArgs, null, -1)
                    + " : " + ex.getMessage();
            Log.e(Global.LOG_CONTEXT, msg, ex);

        }
        return delCount;
    }

    /** converts imageID to content-uri */
    public static Uri getUri(long imageID) {
        return Uri.parse(
                getUriString(imageID));
    }

    @NonNull
    public static String getUriString(long imageID) {
        return SQL_TABLE_EXTERNAL_CONTENT_URI_FILE_NAME + "/" + imageID;
    }

    public static SelectedFiles getSelectedfiles(Context context, String sqlWhere) {
        QueryParameter query = new QueryParameter(FotoSql.queryChangePath);
        query.addWhere(sqlWhere);
        query.addOrderBy(FotoSql.SQL_COL_PATH);

        return getSelectedfiles(context, query);

    }

    @Nullable
    private static SelectedFiles getSelectedfiles(Context context, QueryParameter query) {
        SelectedFiles result = null;
        Cursor c = null;

        try {
            c = FotoSql.createCursorForQuery("getSelectedfiles", context, query, IGalleryFilter.VISIBILITY_PRIVATE_PUBLIC);
            int len = c.getCount();
            Long[] ids = new Long[len];
            String[] paths = new String[len];
            int pkColNo = c.getColumnIndex(FotoSql.SQL_COL_PK);
            int pathColNo = c.getColumnIndex(FotoSql.SQL_COL_PATH);
            int row = 0;
            while (c.moveToNext()) {
                paths[row] = c.getString(pathColNo);
                ids[row] = c.getLong(pkColNo);
                row++;
            }

            result = new SelectedFiles(paths, ids);
        } catch (Exception ex) {
            Log.e(Global.LOG_CONTEXT, "FotoSql.getSelectedfiles() error :", ex);
        } finally {
            if (c != null) c.close();
        }

        if (Global.debugEnabled) {
            Log.d(Global.LOG_CONTEXT, "FotoSql.getSelectedfiles result count=" + ((result != null) ? result.size():0));
        }

        return result;
    }

    /** converts internal ID-list to string array of filenNames via media database. */
    public static String[] getFileNames(Context context, SelectedItems items) {
        if (!items.isEmpty()) {
            ArrayList<String> result = new ArrayList<>();

            QueryParameter parameters = new QueryParameter(queryDetail);
            setWhereSelectionPks(parameters, items);

            Cursor cursor = null;

            try {
                cursor = createCursorForQuery("getFileNames", context, parameters, IGalleryFilter.VISIBILITY_PRIVATE_PUBLIC);

                int colPath = cursor.getColumnIndex(SQL_COL_DISPLAY_TEXT);
                while (cursor.moveToNext()) {
                    String path = cursor.getString(colPath);
                    if ((path != null) && (path.length() > 0)) {
                        result.add(path);
                    }
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            int size = result.size();

            if (size > 0) {
                return result.toArray(new String[size]);
            }
        }
        return null;

    }

    protected static String getFilterExpressionVisibility(int _visibility) {
        int visibility = _visibility;
        // add visibility column only if not included yet
        if (visibility == IGalleryFilter.VISIBILITY_DEFAULT) {
            visibility = (FotoLibGlobal.visibilityShowPrivateByDefault)
                    ? IGalleryFilter.VISIBILITY_PRIVATE_PUBLIC
                    : IGalleryFilter.VISIBILITY_PUBLIC;
        }

        switch (visibility) {
            case IGalleryFilter.VISIBILITY_PRIVATE:
                return FILTER_EXPR_PRIVATE;
            case IGalleryFilter.VISIBILITY_PRIVATE_PUBLIC:
                return FILTER_EXPR_PRIVATE_PUBLIC;
            case IGalleryFilter.VISIBILITY_PUBLIC:
            default:
                return FILTER_EXPR_PUBLIC;
        }
    }

    /** adds visibility to sql of parameters, if not set yet */
    public static QueryParameter setWhereVisibility(QueryParameter parameters, int visibility) {
        if (parameters.toFrom() == null) {
            parameters.addFrom(SQL_TABLE_EXTERNAL_CONTENT_URI_FILE_NAME);
        }
        String sqlWhere = parameters.toAndroidWhere();
        if ((sqlWhere == null) || (parameters.toFrom().contains(SQL_TABLE_EXTERNAL_CONTENT_URI_FILE_NAME) && !sqlWhere.contains(SQL_COL_EXT_MEDIA_TYPE))) {
           parameters.addWhere(getFilterExpressionVisibility(visibility));
        }

        return parameters;
    }

    public static class CursorLoaderWithException extends CursorLoader {
        private final QueryParameter query;
        private Exception mException;

        public CursorLoaderWithException(Context context, QueryParameter query) {
            super(context, Uri.parse(query.toFrom()), query.toColumns(), query.toAndroidWhere(), query.toAndroidParameters(), query.toOrderBy());
            this.query = query;
        }

        @Override
        public Cursor loadInBackground() {
            mException = null;
            try {
                Cursor result = super.loadInBackground();
                return result;
            } catch (Exception ex) {
                final String msg = "FotoSql.createCursorLoader()#loadInBackground failed:\n\t" + query.toSqlString();
                Log.e(Global.LOG_CONTEXT, msg, ex);
                mException = ex;
                return null;
            }
        }

        public QueryParameter getQuery() {
            return query;
        }

        public Exception getException() {
            return mException;
        }
    }
}


