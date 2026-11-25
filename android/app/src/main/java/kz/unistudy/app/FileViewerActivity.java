package kz.unistudy.app;

import android.app.Activity;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.graphics.drawable.*;
import android.net.Uri;
import android.view.View;
import android.widget.*;
import androidx.core.content.FileProvider;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays only retained private files. External viewers receive temporary read grants. */
public final class FileViewerActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private File file; private String name,mime; private int page;
    private ImageView image; private TextView status,pageLabel,text; private Button previous,next;
    private View progress,pages; private boolean destroyed;
    @Override public void onCreate(Bundle state){super.onCreate(state);setContentView(R.layout.content_file_viewer);Ui.insets(findViewById(R.id.content_viewer_root));
        image=findViewById(R.id.content_image);status=findViewById(R.id.content_status);text=findViewById(R.id.content_text);progress=findViewById(R.id.content_loading);pages=findViewById(R.id.content_pages);pageLabel=findViewById(R.id.content_page);previous=findViewById(R.id.content_previous);next=findViewById(R.id.content_next);
        findViewById(R.id.content_back).setOnClickListener(v->finish());
        try{
            String path=getIntent().getStringExtra("path");if(path==null)throw new IOException("Файл жолы берілмеген");
            file=new File(path).getCanonicalFile();String root=getFilesDir().getCanonicalPath()+File.separator;
            if(!file.getPath().startsWith(root)||!file.isFile())throw new IOException("Жеке файл табылмады");
            name=getIntent().getStringExtra("name");if(name==null||name.isEmpty())name=file.getName();
            mime=getIntent().getStringExtra("mime");if(mime==null||mime.isEmpty())mime=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension());if(mime==null)mime="application/octet-stream";
            ((TextView)findViewById(R.id.content_name)).setText(name);findViewById(R.id.content_external).setOnClickListener(v->external());
            page=state==null?0:state.getInt("page",0);
            if(extension().equals("pdf")||mime.equals("application/pdf")){pages.setVisibility(View.VISIBLE);previous.setOnClickListener(v->{page--;pdf();});next.setOnClickListener(v->{page++;pdf();});pdf();}
            else if(extension().equals("txt")||mime.startsWith("text/"))loadText();
            else if(mime.startsWith("image/")||extension().matches("png|jpg|jpeg|gif|webp|bmp"))loadImage();
            else{progress.setVisibility(View.GONE);status.setText("Бұл файлды көру үшін құрылғыдағы сәйкес қолданбаны таңдаңыз.");external();}
        }catch(Exception e){failure(e);findViewById(R.id.content_external).setEnabled(false);}
    }
    private String extension(){int dot=name.lastIndexOf('.');return dot<0?"":name.substring(dot+1).toLowerCase(Locale.ROOT);}
    private void failure(Exception e){if(destroyed)return;progress.setVisibility(View.GONE);status.setText("Файл ашылмады: "+e.getMessage());}
    private void pdf(){progress.setVisibility(View.VISIBLE);previous.setEnabled(false);next.setEnabled(false);int requested=page;
        worker.execute(()->{try(ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(descriptor)){
            int count=renderer.getPageCount();int index=Math.max(0,Math.min(requested,count-1));Bitmap bitmap;
            try(PdfRenderer.Page sheet=renderer.openPage(index)){int width=Math.min(1600,Math.max(800,getResources().getDisplayMetrics().widthPixels));int height=Math.max(1,(int)((double)width*sheet.getHeight()/sheet.getWidth()));if(height>6000){width=Math.max(1,width*6000/height);height=6000;}bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);sheet.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);}
            runOnUiThread(()->{if(destroyed){bitmap.recycle();return;}image.setImageBitmap(bitmap);page=index;pageLabel.setText((page+1)+" / "+count);previous.setEnabled(page>0);next.setEnabled(page+1<count);status.setText("");progress.setVisibility(View.GONE);});
        }catch(Exception e){runOnUiThread(()->failure(e));}});
    }
    private void loadText(){worker.execute(()->{try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){
        byte[] bytes=new byte[8192];int count,total=0;while(total<1024*1024&&(count=in.read(bytes,0,Math.min(bytes.length,1024*1024-total)))!=-1){out.write(bytes,0,count);total+=count;}String value=new String(out.toByteArray(),StandardCharsets.UTF_8);
        runOnUiThread(()->{if(destroyed)return;text.setText(value);image.setVisibility(View.GONE);progress.setVisibility(View.GONE);if(file.length()>1024*1024)status.setText("Алғашқы 1 МБ көрсетілген");});
    }catch(Exception e){runOnUiThread(()->failure(e));}});}
    private void loadImage(){worker.execute(()->{try{
        Drawable drawable;
        if(Build.VERSION.SDK_INT>=28){drawable=ImageDecoder.decodeDrawable(ImageDecoder.createSource(file),(decoder,info,source)->{int factor=Math.max(1,(Math.max(info.getSize().getWidth(),info.getSize().getHeight())+1599)/1600);decoder.setTargetSampleSize(factor);});}
        else{BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),bounds);bounds.inJustDecodeBounds=false;bounds.inSampleSize=Math.max(1,(Math.max(bounds.outWidth,bounds.outHeight)+1599)/1600);Bitmap bitmap=BitmapFactory.decodeFile(file.getPath(),bounds);if(bitmap==null)throw new IOException("Сурет пішімі қолдау таппады");drawable=new BitmapDrawable(getResources(),bitmap);}
        runOnUiThread(()->{if(destroyed)return;image.setImageDrawable(drawable);progress.setVisibility(View.GONE);if(Build.VERSION.SDK_INT>=28&&drawable instanceof AnimatedImageDrawable)((AnimatedImageDrawable)drawable).start();});
    }catch(Exception e){runOnUiThread(()->failure(e));}});}
    private void external(){try{Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",file);Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newRawUri(name,uri));startActivity(Intent.createChooser(intent,"Файлды ашу"));}catch(ActivityNotFoundException e){status.setText("Бұл файл түрін ашатын қолданба орнатылмаған.");}catch(Exception e){failure(e);}}
    @Override public void onSaveInstanceState(Bundle state){state.putInt("page",page);super.onSaveInstanceState(state);}
    @Override public void onDestroy(){destroyed=true;if(Build.VERSION.SDK_INT>=28&&image!=null&&image.getDrawable() instanceof AnimatedImageDrawable)((AnimatedImageDrawable)image.getDrawable()).stop();if(image!=null)image.setImageDrawable(null);worker.shutdownNow();super.onDestroy();}
}

