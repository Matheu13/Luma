package com.dhyn.tvhome;

import android.app.*;import android.os.*;import android.content.*;import android.content.pm.*;import android.graphics.*;import android.graphics.drawable.Drawable;import android.view.*;import android.view.accessibility.*;import java.util.*;import java.util.function.IntConsumer;import java.io.*;

// Luma: a TV home screen drawn on one canvas. Remote keys are handled directly and every pop-up is a Luma panel.
public class MainActivity extends Activity {

static final String[] THEMES={"lunar","aurora","cinema","prism","nebula","daylight"},TITLES={"Lunar","Aurora","Cinema Shelf","Prism","Nebula","Daylight"};
static final int[] ACCENT={0xffc9d6e8,0xff6ff3e6,0xffe8b05c,0xffd98cff,0xffa48bff,0xff3d7cf5};
// Daylight shows every app on the home screen: the dock in the first row, the rest below.
static final int DAYLIGHT=5;
// Dock apps used until the viewer saves their own; any that are not installed are skipped.
static final String[] DEFAULTS={"com.amazon.firetv.youtube","com.google.android.youtube.tv","com.netflix.ninja","com.amazon.firebat","com.amazon.amazonvideo.livingroom","com.disney.disneyplus","com.plexapp.android","org.xbmc.kodi","com.spotify.tv.android"};
static final int DOCK=7;

int theme=0;Screen screen;ArrayList<App> apps=new ArrayList<>(),favourites=new ArrayList<>();boolean all=false,moving=false,held=false,fireTv;int selected=0,nav=-1;Panel panel;long hintUntil;

class App {String name,pkg;Drawable icon,banner;int tone;Bitmap shelf;App(String n,String p,Drawable d,Drawable b){name=n;pkg=p;icon=d;banner=b;}
// Daylight's top-area backdrop, built once per app at half size: a deep wash of the app's colour over its art squeezed
// to 12x7 and enlarged with filtering (so only its colours survive, no shapes), dark enough for the white title, and
// faded out below the title so it melts into the wallpaper. Baking it keeps each frame to a single bitmap draw.
Bitmap shelf(){if(shelf==null||shelf.isRecycled()){Drawable d=banner!=null?banner:icon;Bitmap s=Bitmap.createBitmap(12,7,Bitmap.Config.ARGB_8888);if(d!=null){d.setBounds(0,0,12,7);d.draw(new Canvas(s));}
shelf=Bitmap.createBitmap(836,235,Bitmap.Config.ARGB_8888);Canvas k=new Canvas(shelf);Paint q=new Paint(Paint.FILTER_BITMAP_FLAG);k.drawBitmap(s,null,new Rect(0,0,836,235),q);s.recycle();
int t=tone();q.setShader(new LinearGradient(0,0,836,0,dark(t,0.36f,0xf0),dark(t,0.52f,0xd8),Shader.TileMode.CLAMP));k.drawRect(0,0,836,235,q);
q.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));q.setShader(new LinearGradient(0,190,0,235,0xff000000,0,Shader.TileMode.CLAMP));k.drawRect(0,190,836,235,q);}return shelf;}
int dark(int c,float f,int a){return Color.argb(a,(int)(Color.red(c)*f),(int)(Color.green(c)*f),(int)(Color.blue(c)*f));}
// Average of the icon's saturated pixels; tints the app's card.
int tone(){if(tone==0){tone=0xff2a3350;try{Bitmap b=Bitmap.createBitmap(24,24,Bitmap.Config.ARGB_8888);icon.setBounds(0,0,24,24);icon.draw(new Canvas(b));long r=0,g=0,bl=0,n=0;float[] hsv=new float[3];for(int y=0;y<24;y++)for(int x=0;x<24;x++){int px=b.getPixel(x,y);if(Color.alpha(px)<200)continue;Color.colorToHSV(px,hsv);if(hsv[1]<0.35f||hsv[2]<0.25f)continue;r+=Color.red(px);g+=Color.green(px);bl+=Color.blue(px);n++;}if(n>8)tone=Color.rgb((int)(r/n),(int)(g/n),(int)(bl/n));b.recycle();}catch(Exception ignored){}}return tone;}}

// A Luma-styled list panel. Picking closes it before the action runs; Back returns to the parent panel.
class Panel {String title;String[] items;int sel,checked=-1,top;IntConsumer pick;Panel parent;Panel(String t,String[] i,Panel from){title=t;items=i;parent=from;}}
void show(Panel q,IntConsumer pick){q.pick=pick;panel=q;screen.invalidate();}

Handler clockHandler=new Handler();boolean active=false;Runnable tick=new Runnable(){public void run(){if(active){screen.invalidate();clockHandler.postDelayed(this,1000);}}};
BroadcastReceiver packageObserver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){refreshApps();}};
Intent appIntent(String pkg){PackageManager pm=getPackageManager();Intent i=pm.getLeanbackLaunchIntentForPackage(pkg);return i!=null?i:pm.getLaunchIntentForPackage(pkg);}
App makeApp(String pkg){PackageManager pm=getPackageManager();Intent in=appIntent(pkg);if(in==null)return null;try{ApplicationInfo ai=pm.getApplicationInfo(pkg,0);Drawable b=null;try{b=pm.getActivityBanner(in);if(b==null)b=pm.getApplicationBanner(ai);}catch(Exception ignored){}return new App(pm.getApplicationLabel(ai).toString(),pkg,pm.getApplicationIcon(ai),b);}catch(Exception e){return null;}}
void refreshApps(){String focus=visible().size()>selected?visible().get(selected).pkg:null;ArrayList<App> found=new ArrayList<>();Set<String> seen=new HashSet<>();seen.add(getPackageName());PackageManager pm=getPackageManager();
for(String pkg:DEFAULTS){App a=seen.add(pkg)?makeApp(pkg):null;if(a!=null)found.add(a);}
for(String category:new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER,Intent.CATEGORY_LAUNCHER})for(ResolveInfo r:pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category),0)){String pkg=r.activityInfo.packageName;if(!seen.add(pkg))continue;App a=makeApp(pkg);if(a!=null)found.add(a);}
for(App a:apps)if(a.shelf!=null)a.shelf.recycle();apps=found;restoreOrder();selected=0;for(int j=0;focus!=null&&j<visible().size();j++)if(visible().get(j).pkg.equals(focus))selected=j;if(screen!=null)screen.invalidate();}

// One settings file for every launch entry. Activity.getPreferences was keyed by the entry Luma was opened through, which kept two diverging copies.
SharedPreferences prefs(){return getSharedPreferences("luma",0);}
void migratePrefs(){SharedPreferences p=prefs();if(p.contains("dockOrder")||p.contains("appOrder")||p.contains("theme"))return;
for(String old:new String[]{"com.dhyn.tvhome.LumaLauncher","com.dhyn.tvhome.MainActivity"}){SharedPreferences o=getSharedPreferences(old,0);if(!o.contains("dockOrder"))continue;SharedPreferences.Editor e=p.edit();for(Map.Entry<String,?> en:o.getAll().entrySet()){Object v=en.getValue();if(v instanceof String)e.putString(en.getKey(),(String)v);else if(v instanceof Integer)e.putInt(en.getKey(),(Integer)v);}e.commit();return;}}

public void onCreate(Bundle b){super.onCreate(b);getWindow().setFlags(1024,1024);migratePrefs();fireTv=getPackageManager().hasSystemFeature("amazon.hardware.fire_tv");
// Fire TV keeps Amazon's launcher as Home (Home on Fire hands over to Luma); elsewhere Luma offers itself as a Home app.
if(!fireTv)try{getPackageManager().setComponentEnabledSetting(new ComponentName(this,"com.dhyn.tvhome.LumaHome"),PackageManager.COMPONENT_ENABLED_STATE_ENABLED,PackageManager.DONT_KILL_APP);}catch(Exception ignored){}
theme=Math.max(0,Math.min(5,prefs().getInt("theme",0)));refreshApps();screen=new Screen();setContentView(screen);IntentFilter f=new IntentFilter();f.addAction(Intent.ACTION_PACKAGE_ADDED);f.addAction(Intent.ACTION_PACKAGE_REMOVED);f.addAction(Intent.ACTION_PACKAGE_CHANGED);f.addDataScheme("package");registerReceiver(packageObserver,f);}
// Home pressed again while Luma is open: back to the dock.
@Override protected void onNewIntent(Intent i){super.onNewIntent(i);if(moving){moving=false;saveOrder();}panel=null;nav=-1;all=false;hintUntil=System.currentTimeMillis()+6000;
if(theme==DAYLIGHT){selected=Math.min(selected%7,Math.max(0,favourites.size()-1));screen.scroll=screen.scrollTarget=0;}screen.invalidate();}
@Override protected void onResume(){super.onResume();refreshApps();active=true;hintUntil=System.currentTimeMillis()+6000;clockHandler.removeCallbacks(tick);clockHandler.post(tick);}
@Override protected void onPause(){active=false;clockHandler.removeCallbacks(tick);super.onPause();}
@Override protected void onDestroy(){clockHandler.removeCallbacks(tick);unregisterReceiver(packageObserver);super.onDestroy();}
// Consume TV navigation before Android's focus search can swallow arrows.
@Override public boolean dispatchKeyEvent(KeyEvent e){int k=e.getKeyCode();if(k==19||k==20||k==21||k==22||k==23||k==66||k==82||k==4){return e.getAction()==KeyEvent.ACTION_DOWN?onKeyDown(k,e):onKeyUp(k,e);}return super.dispatchKeyEvent(e);}
ArrayList<App> visible(){if(daylight()){ArrayList<App> v=new ArrayList<>(favourites);v.addAll(rest());return v;}return all?apps:favourites;}
boolean daylight(){return theme==DAYLIGHT&&!all;}
ArrayList<App> rest(){ArrayList<App> r=new ArrayList<>();for(App a:apps)if(!favourites.contains(a))r.add(a);return r;}
boolean inDock(int i){return !all&&(theme!=DAYLIGHT||i<favourites.size());}
// Swaps two visible apps in the list they belong to; in Daylight an app cannot cross between the dock row and the rest.
boolean swapVisible(int a,int b){if(!daylight()){Collections.swap(visible(),a,b);return true;}int f=favourites.size();if((a<f)!=(b<f))return false;if(a<f){Collections.swap(favourites,a,b);return true;}ArrayList<App> v=visible();Collections.swap(apps,apps.indexOf(v.get(a)),apps.indexOf(v.get(b)));return true;}
void saveOrder(){StringBuilder b=new StringBuilder();for(App a:apps){if(b.length()>0)b.append("|");b.append(a.pkg);}StringBuilder dock=new StringBuilder();for(App a:favourites){if(dock.length()>0)dock.append("|");dock.append(a.pkg);}prefs().edit().putString("appOrder",b.toString()).putString("dockOrder",dock.toString()).apply();}

void restoreOrder(){String saved=prefs().getString("appOrder","");ArrayList<App> ordered=new ArrayList<>();for(String pkg:saved.split("\\|")){for(App a:apps)if(a.pkg.equals(pkg)&&!ordered.contains(a))ordered.add(a);}for(App a:apps)if(!ordered.contains(a))ordered.add(a);apps=ordered;String dock=prefs().getString("dockOrder",saved);favourites=new ArrayList<>();for(String pkg:dock.split("\\|")){for(App a:apps)if(a.pkg.equals(pkg)&&!favourites.contains(a)&&favourites.size()<DOCK)favourites.add(a);}for(App a:apps)if(favourites.size()<DOCK&&!favourites.contains(a))favourites.add(a);}

void putInDock(int slot,App chosen){int existing=favourites.indexOf(chosen);if(existing>=0)Collections.swap(favourites,slot,existing);else favourites.set(slot,chosen);saveOrder();}
void chooseFavourite(int slot,Panel from){String[] labels=new String[apps.size()];for(int j=0;j<apps.size();j++)labels[j]=apps.get(j).name;Panel q=new Panel("Dock slot "+(slot+1),labels,from);q.sel=Math.max(0,apps.indexOf(favourites.get(slot)));show(q,w->{putInDock(slot,apps.get(w));selected=slot;});}
void appOptions(){if(visible().isEmpty())return;App a=visible().get(selected);boolean dock=inDock(selected);Panel q=new Panel(a.name,new String[]{"Move app",dock?"Choose app for this slot":"Add to dock","Cancel"},null);
show(q,w->{if(w==0){moving=true;hintUntil=Long.MAX_VALUE;}else if(w==1&&dock)chooseFavourite(selected,q);else if(w==1){String[] slots=new String[favourites.size()];for(int j=0;j<slots.length;j++)slots[j]="Slot "+(j+1)+": "+favourites.get(j).name;show(new Panel("Add "+a.name+" to dock",slots,q),slot->putInDock(slot,a));}screen.invalidate();});}
@Override public boolean onKeyUp(int k,KeyEvent e){if(k==23||k==66){if(panel==null&&nav<0&&!held&&!moving)launch(selected);held=false;return true;}return super.onKeyUp(k,e);}

void menu(){Panel q=new Panel("Luma",new String[]{"Home","All apps","Settings","Arrange selected app"},null);show(q,w->{if(w==2)settingsPanel(q);else if(w==3)appOptions();else{all=w==1;selected=0;nav=-1;}});}
void settingsPanel(Panel from){Panel q=new Panel("Settings",new String[]{"Appearance",fireTv?"Fire TV Settings":"TV Settings","About Luma"},from);show(q,w->{if(w==0)appearance(q);else if(w==1)systemSettings();else about(q);});}
void appearance(Panel from){Panel q=new Panel("Appearance",TITLES,from);q.sel=q.checked=theme;show(q,w->{theme=w;selected=0;prefs().edit().putInt("theme",theme).apply();screen.load();});}
void about(Panel from){String v="";try{v=getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception ignored){}show(new Panel("Luma "+v,new String[]{"github.com/Matheu13/Luma","Close"},from),w->{});}
void message(String m){show(new Panel(m,new String[]{"OK"},null),w->{});}

// Fire TV's Settings grid opens on top of Luma, so Back returns here. The generic Settings action on Fire TV detours through Amazon's home, which Home on Fire redirects back to Luma.
void systemSettings(){try{if(!fireTv)throw new Exception();startActivity(new Intent().setClassName("com.amazon.tv.launcher","com.amazon.tv.launcher.ui.MainSettingsActivity"));}catch(Exception e){try{startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));}catch(Exception ignored){message("Settings could not be opened.");}}}

void launch(int i){try{Intent in=appIntent(visible().get(i).pkg);if(in==null)throw new Exception();startActivity(in);}catch(Exception e){message("This app can't be opened.");}}

boolean panelKey(int k,KeyEvent e){Panel q=panel;int m=q.items.length;if((k==23||k==66)&&e.getRepeatCount()>0)return true;
if(k==19)q.sel=(q.sel+m-1)%m;else if(k==20)q.sel=(q.sel+1)%m;else if(k==23||k==66){held=true;panel=null;q.pick.accept(q.sel);}else if(k==4)panel=q.parent;else if(k==82)panel=null;screen.invalidate();return true;}

public boolean onKeyDown(int k,KeyEvent e){if(panel!=null)return panelKey(k,e);if(k==82){nav=-1;menu();return true;}if(apps.isEmpty())return true;int n=visible().size();
if((k==23||k==66)&&nav<0){if(moving){moving=false;hintUntil=0;saveOrder();held=true;screen.invalidate();return true;}if(e.getRepeatCount()>0&&!held){held=true;appOptions();}return true;}
if(moving){int old=selected,step=all?7:theme==4?4:7;if(k==21)selected=Math.max(0,selected-1);else if(k==22)selected=Math.min(n-1,selected+1);else if(k==19)selected=Math.max(0,selected-step);else if(k==20)selected=Math.min(n-1,selected+step);else if(k==4){moving=false;hintUntil=0;saveOrder();}if(!swapVisible(old,selected))selected=old;screen.invalidate();return true;}
int cols=all?7:(theme==4?4:7);
if(daylight()&&k>=19&&k<=22){screen.tiltX=k==21?-1:k==22?1:0;screen.tiltY=k==19?-1:k==20?1:0;screen.tiltAt=System.currentTimeMillis();}
if(nav>=0){if(k==21)nav=(nav+2)%3;else if(k==22)nav=(nav+1)%3;else if(k==20||k==4)nav=-1;else if(k==23||k==66){held=true;if(nav==2)settingsPanel(null);else{all=nav==1;selected=0;nav=-1;}}screen.invalidate();return true;}
if(k==19&&selected<cols){nav=all?1:0;screen.invalidate();return true;}
// Back at the top level does nothing: a launcher has nowhere to go back to, and finishing would flash Amazon's home.
if(k==4){if(all){all=false;selected=0;screen.invalidate();}return true;}
// Daylight scrolls through rows instead of wrapping: Down stops at the last row, landing on its nearest app.
if(daylight()&&(k==20||k==19)){if(k==20&&selected/7<(n-1)/7)selected=Math.min(selected+7,n-1);else if(k==19)selected-=7;screen.invalidate();return true;}
if(k==21)selected=(selected+n-1)%n;else if(k==22)selected=(selected+1)%n;else if(k==20)selected=(selected+cols)%n;else if(k==19)selected=(selected-cols+n)%n;else return super.onKeyDown(k,e);screen.invalidate();return true;}

class Screen extends View {
Paint p=new Paint(3);Bitmap bg,frost;RectF[] hit=new RectF[0];RectF[] rowHit=new RectF[0];int rowFirst;float scale;
String[] greetings={"Good evening.","Your night starts here.","An evening well spent.","Set the mood.",""};
Screen(){super(MainActivity.this);setFocusable(true);requestFocus();load();}
Bitmap asset(String f){try(InputStream i=getAssets().open(f)){return BitmapFactory.decodeStream(i);}catch(Exception e){return null;}}
// The frosted copy is the wallpaper shrunk then enlarged with filtering, which blurs it cheaply.
void load(){if(bg!=null)bg.recycle();if(frost!=null)frost.recycle();bg=asset(THEMES[theme]+"-wallpaper.png");frost=null;if(bg!=null){Bitmap s=Bitmap.createScaledBitmap(bg,Math.max(1,bg.getWidth()/28),Math.max(1,bg.getHeight()/28),true);frost=Bitmap.createScaledBitmap(s,bg.getWidth()/2,bg.getHeight()/2,true);s.recycle();}invalidate();}
String label(String s,float width,float size){android.text.TextPaint tp=new android.text.TextPaint(3);tp.setTextSize(size);tp.setTypeface(Typeface.create("sans-serif-light",0));return android.text.TextUtils.ellipsize(s,tp,width,android.text.TextUtils.TruncateAt.END).toString();}
void drawArt(Canvas c,Drawable d,RectF b){float w=Math.max(1,d.getIntrinsicWidth()),h=Math.max(1,d.getIntrinsicHeight());float z=Math.min(b.width()/w,b.height()/h);int l=(int)(b.centerX()-w*z/2),t=(int)(b.centerY()-h*z/2);d.setBounds(l,t,(int)(l+w*z),(int)(t+h*z));d.draw(c);}
void text(Canvas c,String s,float x,float y,float size,int color){p.setColor(color);p.setTypeface(Typeface.create("sans-serif-light",0));p.setTextSize(size);c.drawText(s,x,y,p);}
void round(Canvas c,RectF r,int color,float rad){p.setShader(null);p.setColor(color);p.setStyle(Paint.Style.FILL);c.drawRoundRect(r,rad,rad,p);}
void outline(Canvas c,RectF r,int color,float width,float rad){p.setShader(null);p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(width);c.drawRoundRect(r,rad,rad,p);p.setStyle(Paint.Style.FILL);}
int shade(int c,float f){return Color.rgb(Math.min(255,(int)(Color.red(c)*f)),Math.min(255,(int)(Color.green(c)*f)),Math.min(255,(int)(Color.blue(c)*f)));}
int alpha(int c,int a){return (c&0xffffff)|(a<<24);}
// Draws a region of a full-screen bitmap (wallpaper or its frosted copy) into r, matching the wallpaper underneath.
void slice(Canvas c,Bitmap b,RectF src,RectF r){if(b==null)return;float sx=b.getWidth()/1672f,sy=b.getHeight()/941f;c.drawBitmap(b,new Rect((int)(src.left*sx),(int)(src.top*sy),(int)(src.right*sx),(int)(src.bottom*sy)),r,p);}

protected void onDraw(Canvas real){super.onDraw(real);long now=System.currentTimeMillis();boolean animating=false;scale=Math.min(getWidth()/1672f,getHeight()/941f);real.save();real.translate((getWidth()-1672*scale)/2,(getHeight()-941*scale)/2);real.scale(scale,scale);Canvas c=real;
c.drawColor(Color.rgb(5,8,15));p.setShader(null);p.setAlpha(255);if(bg!=null)c.drawBitmap(bg,null,new RectF(0,0,1672,941),p);if(all){p.setColor(0xcc060914);c.drawRect(0,0,1672,941,p);}
int accent=ACCENT[theme];boolean dl=daylight();if(dl)animating|=daylightHome(c,now);else{scroll=scrollTarget=0;lift=null;tiltX=tiltY=0;}
// Top bar: Home, Apps, Settings gear, time and date. Over Daylight's light rows (once scrolled) it turns dark.
boolean darkBar=dl&&scroll>=100;int ink=darkBar?0xff1c1f26:Color.WHITE;
text(c,"Home",65,70,25,all?0xffaaaaaa:ink);text(c,"Apps",185,70,25,all?Color.WHITE:darkBar?0xff6a707c:0xffaaaaaa);gear(c,1440,58,17,ink);
text(c,new java.text.SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date()),1510,66,30,ink);text(c,new java.text.SimpleDateFormat("EEE d MMM",Locale.getDefault()).format(new Date()),1512,92,17,darkBar?0xff4a4f5a:0xffc0c5cf);

if(!all&&theme!=4&&theme!=DAYLIGHT){int hour=Calendar.getInstance().get(Calendar.HOUR_OF_DAY);greetings[0]=hour<12?"Good morning.":hour<18?"Good afternoon.":"Good evening.";float y=theme==0?330:theme==2?295:280;if(theme==1){p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));p.setTextSize(76);p.setColor(Color.WHITE);c.drawText("Your night",60,235,p);p.setShader(new LinearGradient(60,0,570,0,new int[]{0xff9affee,0xff8872ff},null,Shader.TileMode.CLAMP));c.drawText("starts here.",60,320,p);p.setShader(null);}else text(c,greetings[theme],60,y,theme==2?68:70,Color.WHITE);}if(all)text(c,"Your apps",60,150,52,Color.WHITE);

int n=visible().size();if(dl)n=0;else hit=new RectF[n];
int cols=all?7:(theme==4?4:7);float tileW=all?195:theme==4?225:theme==2?207:201;float tileH=all?145:theme==4?210:theme==2?420:theme==0?162:theme==1?195:210;float left=all?60:theme==4?620:60;float top=all?200:theme==4?305:theme==2?365:theme==0?660:390;float gap=all?25:theme==4?18:20;
// All apps shows four rows, keeping the selected row third from the top.
int rowOffset=all?Math.max(0,selected/7-2):0;
for(int i=0;i<n;i++){int row=i/cols-rowOffset,col=i%cols;if(row<0||all&&row>3)continue;
RectF r=new RectF(left+col*(tileW+gap),top+row*(tileH+22),left+col*(tileW+gap)+tileW,top+row*(tileH+22)+tileH);hit[i]=r;
card(c,visible().get(i),r);if(!all&&theme==1)text(c,label(visible().get(i).name,r.width()-15,23),r.left+15,r.bottom+34,23,Color.WHITE);
// Focus: a plain outline on the selected tile, moving straight there with each key press, as in the original Luma.
if(i==selected&&nav<0)outline(c,r,theme==1?0xff9bf6ff:Color.WHITE,4,18);}
if(nav>=0)outline(c,navRect(nav),Color.WHITE,3,10);

if(!all&&theme==4&&n>0){App a=visible().get(Math.min(selected,n-1));RectF plate=new RectF(155,300,375,490);c.save();Path clip=new Path();clip.addRoundRect(plate,22,22,Path.Direction.CW);c.clipPath(clip);slice(c,frost,plate,plate);p.setShader(new LinearGradient(0,plate.top,0,plate.bottom,alpha(a.tone(),0x99),0x22000000,Shader.TileMode.CLAMP));c.drawRect(plate,p);p.setShader(null);c.restore();outline(c,plate,0x40ffffff,1.5f,22);Drawable art=a.banner!=null?a.banner:a.icon;if(art!=null){RectF in=new RectF(plate);in.inset(22,a.banner!=null?48:34);drawArt(c,art,in);}text(c,label(a.name,440,60),155,560,60,Color.WHITE);text(c,"Open app  ›",155,655,30,Color.WHITE);}

// Hint fades out a few seconds after the screen appears; move mode keeps it on.
float ha=moving?1:Math.max(0,Math.min(1,(hintUntil-now)/600f));if(ha>0){text(c,moving?"Move mode: arrows move the app, OK saves":"Hold OK to arrange   ·   Menu for options",60,915,18,alpha(daylight()?0xff4a4f5a:0xffc0c5cf,(int)(255*ha)));if(ha<1)animating=true;}

if(panel!=null)drawPanel(c,accent);real.restore();announce();if(animating)postInvalidateOnAnimation();}

void gear(Canvas c,float cx,float cy,float r,int color){p.setShader(null);p.setColor(color);p.setStyle(Paint.Style.FILL);for(int t=0;t<8;t++){double a=t*Math.PI/4;c.save();c.rotate((float)Math.toDegrees(a),cx,cy);c.drawRoundRect(new RectF(cx-3.5f,cy-r-5,cx+3.5f,cy-r+4),2,2,p);c.restore();}p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(5);c.drawCircle(cx,cy,r-2,p);p.setStrokeWidth(3);c.drawCircle(cx,cy,5,p);p.setStyle(Paint.Style.FILL);}


// App card, built only from the installed app's own banner or icon: glossy tile (Aurora), scenic poster (Cinema Shelf) or frosted glass.
void card(Canvas c,App a,RectF r){int t=a.tone();c.save();Path clip=new Path();clip.addRoundRect(r,18,18,Path.Direction.CW);c.clipPath(clip);p.setStyle(Paint.Style.FILL);
if(!all&&theme==1){p.setShader(new LinearGradient(r.left,r.top,r.right,r.bottom,shade(t,1.1f),shade(t,0.45f),Shader.TileMode.CLAMP));c.drawRect(r,p);p.setShader(new LinearGradient(0,r.top,0,r.centerY(),0x2effffff,0x00ffffff,Shader.TileMode.CLAMP));c.drawRect(r,p);}
else if(!all&&theme==2){slice(c,bg,new RectF(r.left,40,r.right,40+r.height()),r);p.setShader(new LinearGradient(0,r.top,0,r.bottom,new int[]{alpha(t,0x88),alpha(shade(t,0.5f),0xaa),0xf005070c},new float[]{0,0.55f,1},Shader.TileMode.CLAMP));c.drawRect(r,p);}
else{slice(c,frost,r,r);p.setShader(null);p.setColor(theme==0&&!all?0x99101318:0x8810131c);c.drawRect(r,p);p.setShader(new LinearGradient(0,r.top,0,r.bottom,alpha(t,theme==0&&!all?0x26:0x55),0x00000000,Shader.TileMode.CLAMP));c.drawRect(r,p);p.setShader(new LinearGradient(0,r.top,0,r.top+r.height()*0.35f,0x18ffffff,0x00ffffff,Shader.TileMode.CLAMP));c.drawRect(r,p);}
p.setShader(null);c.restore();if(all||theme!=1)outline(c,r,0x30ffffff,1.5f,18);
float s=all?62:theme==0?74:theme==1?110:theme==2?110:88,cy=all?r.top+56:r.top+r.height()*(theme==1?0.5f:theme==2?0.42f:theme==4?0.43f:0.41f);Drawable art=a.banner!=null?a.banner:a.icon;
if(art!=null){RectF box=a.banner!=null?new RectF(r.left+16,cy-s*0.62f,r.right-16,cy+s*0.62f):new RectF(r.centerX()-s*0.7f,cy-s/2,r.centerX()+s*0.7f,cy+s/2);drawArt(c,art,box);}
if(all||theme!=1){float size=all?19:theme==2?30:theme==4?24:theme==3?22:20;p.setTypeface(Typeface.create("sans-serif-light",0));p.setTextSize(size);while(size>16&&p.measureText(a.name)>r.width()-24)p.setTextSize(--size);p.setTextAlign(Paint.Align.CENTER);text(c,label(a.name,r.width()-24,size),r.centerX(),r.bottom-(all?16:theme==2?44:theme==0?16:20),size,theme==0&&!all?0xffd8dbe0:Color.WHITE);p.setTextAlign(Paint.Align.LEFT);}}

// Daylight home: a big top area for the focused favourite, then every app in rows, all scrolled as one page. Returns true while still moving.
float scroll,scrollTarget;long lastFrame,tiltAt;int shelfIndex,tiltX,tiltY;float[] lift;Camera camera=new Camera();Matrix tiltMatrix=new Matrix();
// The lifted tile's soft shadow; blurred shadows draw with hardware acceleration from Android 9, older TVs simply go without.
Paint shadow=new Paint(Paint.ANTI_ALIAS_FLAG);{shadow.setMaskFilter(new BlurMaskFilter(22,BlurMaskFilter.Blur.NORMAL));}
boolean daylightHome(Canvas c,long now){ArrayList<App> v=visible();int n=v.size(),f=favourites.size(),row=selected/7;hit=new RectF[n];
float dt=Math.max(0,Math.min(50,now-lastFrame));lastFrame=now;scrollTarget=row==0?0:200+160*row;scroll+=(scrollTarget-scroll)*(float)(1-Math.exp(-dt/66.0));boolean sliding=Math.abs(scrollTarget-scroll)>0.5f;if(!sliding)scroll=scrollTarget;
// Focus lift per tile, 0..1, eased over 150 ms; the list can change size when apps are installed or removed.
if(lift==null||lift.length!=n){lift=new float[n];if(selected<n&&nav<0)lift[selected]=1;}
for(int i=0;i<n;i++){float target=i==selected&&nav<0?1:0;lift[i]=target>lift[i]?Math.min(target,lift[i]+dt/150f):Math.max(target,lift[i]-dt/150f);if(lift[i]>0&&lift[i]<1)sliding=true;}
float tilt=Math.max(0,1-(now-tiltAt)/300f);if(tilt>0)sliding=true;
if(selected<f)shelfIndex=selected;c.save();c.translate(0,-scroll);
if(f>0&&scroll<470){App a=favourites.get(Math.min(shelfIndex,f-1));p.setShader(null);p.setAlpha(255);c.drawBitmap(a.shelf(),null,new RectF(0,0,1672,470),p);
Drawable art=a.banner!=null?a.banner:a.icon;if(art!=null){RectF box=new RectF(80,170,500,406);c.save();Path clip=new Path();clip.addRoundRect(box,16,16,Path.Direction.CW);c.clipPath(clip);drawArt(c,art,box);c.restore();}text(c,label(a.name,900,64),620,300,64,Color.WHITE);text(c,"Open app  ›",620,370,30,Color.WHITE);}
// The selected tile is drawn last so its lifted, larger card sits over its neighbours. Rows scrolled off screen are skipped.
for(int pass=0;pass<2;pass++)for(int i=0;i<n;i++){if((i==selected)!=(pass==1))continue;RectF r=daylightRect(i);RectF on=new RectF(r);on.offset(0,-scroll);hit[i]=on;if(on.bottom<-40||on.top>981)continue;float e=1-(float)Math.pow(1-lift[i],3);daylightCard(c,v.get(i),r,e,i==selected?tilt:0);}
if(nav<0&&selected<n){RectF r=daylightRect(selected);p.setTextAlign(Paint.Align.CENTER);text(c,label(v.get(selected).name,260,22),r.centerX(),r.bottom+34,22,0xff1c1f26);p.setTextAlign(Paint.Align.LEFT);}
c.restore();return sliding;}
// Daylight's tiles: seven to a row, 200x120, laid out in content space (before scrolling).
RectF daylightRect(int i){int r=i/7,col=i%7;float l=52+col*(200+28),t=500+r*(120+40);return new RectF(l,t,l+200,t+120);}
// A rounded tile tinted with the app's colour, showing its TV banner, or its icon when it has none.
// e (0..1, eased) lifts it: larger, a soft shadow, a slight tilt towards the last arrow (tilt fades 1..0) and a shine.
void daylightCard(Canvas c,App a,RectF r,float e,float tilt){int t=a.tone();float cx=r.centerX(),cy=r.centerY();c.save();
if(e>0){float z=1+0.12f*e;c.scale(z,z,cx,cy);if(Build.VERSION.SDK_INT>=28){RectF sh=new RectF(r);sh.offset(0,12*e);shadow.setColor(alpha(0xff000000,(int)(0x70*e)));c.drawRoundRect(sh,18,18,shadow);}
if(tilt>0){camera.save();camera.rotateY(4*tiltX*e*tilt);camera.rotateX(-4*tiltY*e*tilt);camera.getMatrix(tiltMatrix);camera.restore();tiltMatrix.preTranslate(-cx,-cy);tiltMatrix.postTranslate(cx,cy);c.concat(tiltMatrix);}}
c.save();Path clip=new Path();clip.addRoundRect(r,18,18,Path.Direction.CW);c.clipPath(clip);
p.setColor(0xff000000);p.setShader(new LinearGradient(0,r.top,0,r.bottom,shade(t,1.15f),shade(t,0.8f),Shader.TileMode.CLAMP));c.drawRect(r,p);p.setShader(null);
if(a.banner!=null){RectF in=new RectF(r);in.inset(10,10);drawArt(c,a.banner,in);}else if(a.icon!=null)drawArt(c,a.icon,new RectF(cx-32,cy-32,cx+32,cy+32));
if(e>0){p.setColor(0xff000000);p.setShader(new LinearGradient(r.left,r.top,cx,cy,alpha(0xffffffff,(int)(0x40*e)),0x00ffffff,Shader.TileMode.CLAMP));c.drawRect(r,p);p.setShader(null);}
c.restore();outline(c,r,0x40ffffff,1.5f,18);c.restore();}

// Daylight's panels are light frosted glass with dark text; every other theme's are dark.
void drawPanel(Canvas c,int accent){Panel q=panel;int rows=Math.min(q.items.length,8);float w=660,rowH=58,h=116+rows*rowH;float x=(1672-w)/2,y=(941-h)/2;boolean light=theme==DAYLIGHT;int ink=light?0xff1c1f26:Color.WHITE,dim=light?0xff4a4f5a:0xffc0c5cf;
p.setShader(null);p.setColor(light?0x55000000:0xa0000000);c.drawRect(0,0,1672,941,p);RectF box=new RectF(x,y,x+w,y+h);c.save();Path clip=new Path();clip.addRoundRect(box,26,26,Path.Direction.CW);c.clipPath(clip);slice(c,frost,box,box);p.setColor(light?0xe6ffffff:0xd80c1019);c.drawRect(box,p);c.restore();outline(c,box,0x40ffffff,1.5f,26);
text(c,label(q.title,w-72,30),x+36,y+62,30,ink);if(q.sel<q.top)q.top=q.sel;if(q.sel>=q.top+rows)q.top=q.sel-rows+1;rowHit=new RectF[rows];rowFirst=q.top;
for(int i=0;i<rows;i++){int it=q.top+i;RectF r=new RectF(x+20,y+92+i*rowH,x+w-20,y+92+i*rowH+rowH-6);rowHit[i]=r;if(it==q.sel){round(c,r,alpha(accent,0x30),14);outline(c,r,accent,2,14);}text(c,label(q.items[it],w-120,24),r.left+20,r.centerY()+8,24,it==q.sel?ink:dim);if(it==q.checked){p.setColor(accent);c.drawCircle(r.right-28,r.centerY(),7,p);}}
if(q.top>0)text(c,"▲",x+w-46,y+62,18,dim);if(q.top+rows<q.items.length)text(c,"▼",x+w-46,y+h-14,18,dim);}

// Screen-reader support: tiles, top-bar items and panel rows are virtual nodes with names and actions.
String[] navNames={"Home tab","Apps tab","Settings"};String lastSaid="";
String speak(int i){ArrayList<App> v=visible();App a=v.get(i);int f=favourites.size();String where=daylight()?(i<f?"dock "+(i+1)+" of "+f:"app "+(i-f+1)+" of "+(v.size()-f)):(all?"app ":"dock ")+(i+1)+" of "+v.size();return (moving?"Moving ":"")+a.name+", "+where+(moving?". Arrows move, OK saves":"");}
RectF navRect(int k){return k==0?new RectF(50,30,150,90):k==1?new RectF(170,30,275,90):new RectF(1408,26,1472,90);}
Rect toScreen(RectF r){int[] o=new int[2];getLocationOnScreen(o);float ox=o[0]+(getWidth()-1672*scale)/2,oy=o[1]+(getHeight()-941*scale)/2;return new Rect((int)(ox+r.left*scale),(int)(oy+r.top*scale),(int)(ox+r.right*scale),(int)(oy+r.bottom*scale));}
void announce(){String said=panel!=null?panel.title+": "+panel.items[panel.sel]:nav>=0?navNames[nav]:visible().isEmpty()?"No apps":speak(Math.min(selected,visible().size()-1));if(said.equals(lastSaid))return;lastSaid=said;AccessibilityManager am=(AccessibilityManager)getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);if(am!=null&&am.isEnabled())post(()->announceForAccessibility(said));}
AccessibilityNodeProvider provider=new AccessibilityNodeProvider(){
public AccessibilityNodeInfo createAccessibilityNodeInfo(int id){if(id==View.NO_ID){AccessibilityNodeInfo n=AccessibilityNodeInfo.obtain(Screen.this);onInitializeAccessibilityNodeInfo(n);n.setContentDescription(panel!=null?panel.title:all?"Luma, all apps":"Luma home");if(panel!=null){for(int i=0;i<rowHit.length;i++)n.addChild(Screen.this,2000+i);}else{for(int k=0;k<3;k++)n.addChild(Screen.this,1000+k);for(int i=0;i<hit.length;i++)if(hit[i]!=null)n.addChild(Screen.this,i);}return n;}
RectF r;AccessibilityNodeInfo n=AccessibilityNodeInfo.obtain(Screen.this,id);
if(id>=2000){int i=id-2000;if(panel==null||i>=rowHit.length||rowFirst+i>=panel.items.length)return null;int it=rowFirst+i;r=rowHit[i];n.setText(panel.items[it]);n.setSelected(it==panel.sel);n.setChecked(it==panel.checked);n.setAccessibilityFocused(it==panel.sel);}
else if(id>=1000){int k=id-1000;if(k>2)return null;r=navRect(k);n.setText(navNames[k]);n.setSelected(k==0?!all:k==1&&all);n.setAccessibilityFocused(nav==k);}
else{if(id>=hit.length||hit[id]==null||id>=visible().size())return null;r=hit[id];n.setText(visible().get(id).name);n.setContentDescription(speak(id));n.setSelected(id==selected&&nav<0);n.setAccessibilityFocused(id==selected&&nav<0);n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK);n.setLongClickable(true);}
n.setParent(Screen.this);n.setPackageName(getPackageName());n.setClassName("android.widget.Button");n.setEnabled(true);n.setFocusable(true);n.setClickable(true);n.setVisibleToUser(true);n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS);n.setBoundsInScreen(toScreen(r));return n;}
public boolean performAction(int id,int action,Bundle args){if(id==View.NO_ID)return performAccessibilityAction(action,args);
if(id>=2000){if(panel==null)return false;int it=rowFirst+id-2000;if(it>=panel.items.length)return false;panel.sel=it;if(action==AccessibilityNodeInfo.ACTION_CLICK){Panel q=panel;panel=null;q.pick.accept(it);}invalidate();return true;}
if(id>=1000){int k=id-1000;if(action==AccessibilityNodeInfo.ACTION_CLICK){if(k==2)settingsPanel(null);else{all=k==1;selected=0;nav=-1;invalidate();}return true;}if(action==AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS&&!moving){nav=k;invalidate();return true;}return false;}
if(id>=visible().size())return false;if(action==AccessibilityNodeInfo.ACTION_CLICK&&!moving){selected=id;nav=-1;launch(id);return true;}if(action==AccessibilityNodeInfo.ACTION_LONG_CLICK&&!moving){selected=id;nav=-1;appOptions();return true;}if(action==AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS&&!moving){selected=id;nav=-1;invalidate();return true;}return false;}};
@Override public AccessibilityNodeProvider getAccessibilityNodeProvider(){return provider;}

public boolean onTouchEvent(android.view.MotionEvent e){if(e.getAction()!=1)return true;float x=(e.getX()-(getWidth()-1672*scale)/2)/scale,y=(e.getY()-(getHeight()-941*scale)/2)/scale;
if(panel!=null){for(int i=0;i<rowHit.length;i++)if(rowHit[i]!=null&&rowHit[i].contains(x,y)){Panel q=panel;panel=null;q.pick.accept(rowFirst+i);invalidate();return true;}panel=panel.parent;invalidate();return true;}
if(y<100){if(x>1390&&x<1490)settingsPanel(null);else if(x<300){all=x>160;selected=0;invalidate();}return true;}for(int i=0;i<hit.length;i++)if(hit[i]!=null&&hit[i].contains(x,y)){selected=i;launch(i);break;}return true;}}

}
