# DisplayingBitmaps
改写android 示例写的一个 图片三级缓存demo

#如何加载图片到制定Imageview
```
  ImageLoaderHelper mImageFetcher = ImageLoaderHelper.build(this.getApplicationContext(),null);
  mImageFetcher.bindBitmap(Images.imageThumbUrls[position], imageView);
  ```
