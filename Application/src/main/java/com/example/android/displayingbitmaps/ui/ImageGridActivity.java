/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.displayingbitmaps.ui;

import android.content.Context;
import android.os.Bundle;
import android.support.v4.app.FragmentActivity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;

import com.example.android.displayingbitmaps.R;
import com.example.android.displayingbitmaps.provider.Images;
import com.example.android.displayingbitmaps.util.ImageCache;
import com.example.android.displayingbitmaps.util.ImageLoaderHelper;

/**
 * Simple FragmentActivity to hold the main and not much else.
 */
public class ImageGridActivity extends FragmentActivity implements AdapterView.OnItemClickListener  {
    private static final String TAG = "ImageGridActivity";
    private static final String IMAGE_CACHE_DIR = "thumbs";

    private ImageAdapter mAdapter;
    private ImageLoaderHelper mImageFetcher;

    private GridView mGridView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.image_main_layout);
        mGridView = (GridView)this.findViewById(R.id.gridView);

        mAdapter = new ImageAdapter(this);

        ImageCache.ImageCacheParams cacheParams =
                new ImageCache.ImageCacheParams(this, IMAGE_CACHE_DIR);

        cacheParams.setMemCacheSizePercent(0.25f); // Set memory cache to 25% of app memory


        mImageFetcher = ImageLoaderHelper.build(this.getApplicationContext(),cacheParams);

        mGridView.setAdapter(mAdapter);
        mGridView.setOnItemClickListener(this);
    }


    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {

    }

    private class ImageAdapter extends BaseAdapter {

        private final Context mContext;
        private LayoutInflater mLayoutInflater = null;

        public ImageAdapter(Context context) {
            super();
            mContext = context;
            mLayoutInflater = LayoutInflater.from(mContext);
        }

        @Override
        public int getCount() {
            return Images.imageThumbUrls.length ;
        }

        @Override
        public Object getItem(int position) {
            return  Images.imageThumbUrls[position];
        }

        @Override
        public long getItemId(int position) {
            return  position;
        }


        @Override
        public boolean hasStableIds() {
            return true;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup container) {
            ImageView imageView;
            if (convertView == null) { // if it's not recycled, instantiate and initialize
                convertView = mLayoutInflater.inflate(R.layout.image_main_grid_item,container,false);
            } else { // Otherwise re-use the converted view
                imageView = (ImageView) convertView;
            }
           imageView = (ImageView)convertView.findViewById(R.id.imageView);
           imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);

            mImageFetcher.bindBitmap(Images.imageThumbUrls[position], imageView);
            return imageView;
        }
    }

    static class ViewHolder{
         ImageView imageview;
    }


}
