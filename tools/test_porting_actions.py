"""Execute the real porting menu actions and publication with recording JNI stubs."""
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parent.parent
out = root / 'build/porting-actions-tests'
out.mkdir(parents=True, exist_ok=True)
source = (root / 'port/android/app/src/main/java/com/halo/decomp/TouchControls.java').read_text()

def method(name):
    match = re.search(r'^    (?:public|private) void '+name+r'\(', source, re.M)
    start = source.index('{', match.start())
    depth, end = 1, start+1
    while depth:
        depth += (source[end] == '{')-(source[end] == '}')
        end += 1
    return source[match.start():end]

def stub(path, text):
    file = out / path
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_text(text, encoding='utf-8')

stub('android/content/Context.java', '''\
package android.content;
public class Context {
 public static java.io.File testFilesDir;
 public java.io.File getExternalFilesDir(String type) { return testFilesDir; }
}
''')
stub('android/content/DialogInterface.java', '''\
package android.content;
public interface DialogInterface {
 interface OnClickListener { void onClick(DialogInterface dialog, int which); }
}
''')
stub('android/app/AlertDialog.java', '''\
package android.app;
public class AlertDialog {
 public static class Builder {
  android.content.DialogInterface.OnClickListener positive;
  public Builder(android.content.Context context) {}
  public Builder setTitle(String title) { return this; }
  public Builder setMessage(String message) { return this; }
  public Builder setView(Object view) { return this; }
  public Builder setNegativeButton(String text, android.content.DialogInterface.OnClickListener listener) { return this; }
  public Builder setPositiveButton(String text, android.content.DialogInterface.OnClickListener listener) { positive = listener; return this; }
  public void show() { if (positive != null) positive.onClick(null, 0); }
 }
}
''')
stub('android/widget/EditText.java', '''\
package android.widget;
public class EditText {
 public static String nextText;
 String text = "";
 public EditText(android.content.Context context) {}
 public void setText(String value) { text = value; }
 public void setSingleLine() {}
 public String getText() { return nextText != null ? nextText : text; }
}
''')

code = r'''
package com.halo.decomp;
import java.nio.file.*;
public class PortingActionsTest {
 static final int LEFT=TouchLayout.LEFT;
 TouchLayout layout=new TouchLayout();StartupCheats startupCheats;
 int menuContext=1,menuPage=6,revision,selectedControl,requestCount,lastCheat,lastStatus;
 boolean editing,hudEditing,visible;float sensitivity=1,appliedFov;
 String title;String[] labels;int[] actions,values;
 static class Owners {int size(){return 0;}} Owners owners=new Owners();
 static class Device {boolean hasVibrator(){return true;}} Device vibrator=new Device(),gyroscope=new Device();
 static class Toast {static final int LENGTH_SHORT=0,LENGTH_LONG=1;static String last;
  static Toast makeText(Object c,String s,int l){last=s;return new Toast();}void show(){}}
 static class HaloActivity extends android.content.Context {
  boolean importCalled,importModCalled;void importMapFile(){importCalled=true;}
  void importModFolder(){importModCalled=true;}
 }
 HaloActivity halo=new HaloActivity();
 void openCreator(){} android.content.Context getContext(){return halo;}void reset(){}void updateSensors(){}void cancelRumble(){}void invalidate(){}
 java.util.ArrayList<String> mapNames=new java.util.ArrayList<>();
 String selectedMap;static final int MAX_MAP_ROWS=60;
 void exitHudEditing(){hudEditing=false;editing=false;menuPage=3;}
 boolean saveLayout(){return true;}String controlName(int i){return "Button "+i;}
 int nativeCheatStatus(int id){return lastStatus;}
 boolean nativeCheatRequest(int id,boolean enabled){requestCount++;lastCheat=id;lastStatus=enabled?1:0;return true;}
 void nativeFieldOfView(float degrees){appliedFov=degrees;}
 void nativeMenuPublish(int r,int p,boolean e,String t,String[] l,int[] a,int[] v){title=t;labels=l;actions=a;values=v;}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
'''
code += re.search(r'    private static final String\[\] CHEATS = .*?;', source, re.S).group(0)
code += method('applyMenuAction')+method('publishMenu')
code += method('refreshMapList')+method('onMapsChanged')
code += method('renameSelectedMap')+method('deleteSelectedMap')
match = re.search(r'^    private java\.io\.File mapsDirectory\(\)', source, re.M)
start = source.index('{', match.start())
depth, end = 1, start+1
while depth:
    depth += (source[end] == '{')-(source[end] == '}')
    end += 1
code += source[match.start():end]
for static_name, return_type in (('sanitizeMapName', 'String'), ('isResourceMapName', 'boolean')):
    match = re.search(r'^    static '+return_type+r' '+static_name+r'\(', source, re.M)
    start = source.index('{', match.start())
    depth, end = 1, start+1
    while depth:
        depth += (source[end] == '{')-(source[end] == '}')
        end += 1
    code += source[match.start():end]
code += r'''
 public static void main(String[] args)throws Exception {
  Path folder=Files.createTempDirectory("halo-actions-test"),init=folder.resolve("init.txt");
  try {
   PortingActionsTest t=new PortingActionsTest();t.startupCheats=new StartupCheats(init);
   t.publishMenu();check(t.title.equals("STARTUP CHEATS") && t.labels[0].equals("INVINCIBILITY [OFF]"),"Main menu shows startup defaults in capitals");
   t.lastStatus=-1;t.applyMenuAction(3000,0);t.applyMenuAction(3014,0);
   check(t.requestCount==0 && t.labels[0].endsWith("[ON]") && t.labels[14].endsWith("[ON]"),"Main menu writes selected cheats even without a player");
   String saved=Files.readString(init);
   t.menuContext=2;t.lastStatus=0;t.applyMenuAction(3000,0);t.applyMenuAction(3014,0);
   check(t.requestCount==2 && t.lastCheat==14 && saved.equals(Files.readString(init)),"Pause menu retains live cheats without changing init.txt");
   t.menuPage=1;t.publishMenu();check(t.title.equals("PORTING OPTIONS") && t.labels[1].equals("GENERAL") && t.labels[3].equals("BACK"),"General replaces Hardware");
   t.menuPage=3;t.publishMenu();check(t.title.equals("GENERAL") && t.actions[4]==13,"General contains FOV");
   check(t.labels[5].equals("EDIT IN-GAME UI") && t.actions[5]==14 && t.actions[6]==90,"General contains Edit in-game UI before Back");
   for(String label:t.labels)check(label.equals(label.toUpperCase(java.util.Locale.ROOT)),"Every General row is capitals: "+label);
   t.applyMenuAction(14,0);check(t.hudEditing && t.editing && t.menuPage==3,"Edit in-game UI opens its editor over General");
   t.applyMenuAction(30,0);check(t.hudEditing && t.layout.rumbleEnabled,"The editor takes no menu action but Back");
   t.applyMenuAction(90,0);check(!t.hudEditing && !t.editing && t.menuPage==3,"Back leaves the editor for General");
   t.applyMenuAction(13,0);check(t.values[0]>=0 && t.actions[0]==43,"FOV publishes a slider");
   t.applyMenuAction(43,1000);check(t.layout.fieldOfView==90 && t.appliedFov==90 && t.values[0]==1000,"FOV upper endpoint");
   t.applyMenuAction(43,-100);check(t.layout.fieldOfView==55 && t.appliedFov==55,"FOV clamps lower endpoint");
   t.applyMenuAction(90,0);check(t.menuPage==3,"FOV Back returns to General");
   t.menuPage=2;t.applyMenuAction(33,0);check(t.layout.overlayDisabled && t.labels[4].endsWith("ON"),"Disable all overlay toggles on");
   t.applyMenuAction(33,0);check(!t.layout.overlayDisabled,"Overlay can be restored through menus");
   android.content.Context.testFilesDir=folder.toFile();
   java.io.File maps=new java.io.File(folder.toFile(),"custom_maps");maps.mkdir();
   Files.write(maps.toPath().resolve("b_map.map"),new byte[0]);
   Files.write(maps.toPath().resolve("a_map.map"),new byte[0]);
   Files.write(maps.toPath().resolve("bitmaps.map"),new byte[0]);
   Files.write(maps.toPath().resolve("notes.txt"),new byte[0]);
   t.menuContext=1;t.menuPage=1;t.publishMenu();
   check(java.util.Arrays.asList(t.labels).contains("MAPS & MODS"),"Main menu offers Maps & Mods");
   t.menuContext=2;t.publishMenu();
   check(!java.util.Arrays.asList(t.labels).contains("MAPS & MODS"),"Pause menu has no Maps & Mods");
   t.menuContext=1;t.menuPage=15;t.publishMenu();
   check(t.title.equals("MAPS & MODS") && t.labels.length==6 && t.labels[0].equals("IMPORT MAP")
    && t.labels[1].equals("IMPORT MOD") && t.labels[2].equals("A_MAP.MAP")
    && t.labels[3].equals("B_MAP.MAP") && t.labels[4].equals("BITMAPS.MAP [SHARED]")
    && t.labels[5].equals("BACK"),
    "Maps page imports maps and mods, and lists .map files sorted with Back");
   t.applyMenuAction(4000,0);check(t.menuPage==16 && t.selectedMap.equals("a_map.map"),"Tap selects a map");
   t.applyMenuAction(90,0);check(t.menuPage==15,"Map Back returns to the list");
   t.applyMenuAction(90,0);check(t.menuPage==1,"List Back returns to Porting options");
   t.applyMenuAction(15,0);check(t.menuPage==15,"Maps & Mods opens the list");
   t.applyMenuAction(50,0);check(t.halo.importCalled,"Import opens the document picker");
   t.applyMenuAction(53,0);check(t.halo.importModCalled,"Import mod opens its folder picker");
   android.widget.EditText.nextText="renamed";
   t.applyMenuAction(4000,0);t.applyMenuAction(51,0);
   check(new java.io.File(maps,"renamed.map").isFile() && !new java.io.File(maps,"a_map.map").exists()
    && t.menuPage==15,"Rename moves the file");
   android.widget.EditText.nextText=null;
   t.applyMenuAction(4002,0);check(t.selectedMap.equals("renamed.map"),"Renamed map is listed");
   t.applyMenuAction(52,0);
   check(!new java.io.File(maps,"renamed.map").exists() && t.menuPage==15
    && Toast.last.equals("Deleted renamed.map."),"Delete removes the file with a notice");
   System.out.println("Main/pause cheat routing, General, FOV endpoints and global overlay toggle passed");
   System.out.println("Maps & Mods listing, import entry, rename and delete passed");
  } finally {
   java.util.ArrayList<Path> leftovers = new java.util.ArrayList<>();
   try (java.util.stream.Stream<Path> walk = Files.walk(folder)) { walk.forEach(leftovers::add); }
   catch (java.io.IOException e) { throw new RuntimeException(e); }
   leftovers.sort(java.util.Comparator.reverseOrder());
   for (Path leftover : leftovers) Files.deleteIfExists(leftover);
  }
 }
}
'''
(out / 'PortingActionsTest.java').write_text(code, encoding='utf-8')
java = root / 'port/android/app/src/main/java/com/halo/decomp'
subprocess.run(['javac', '-d', str(out), str(out / 'android/content/Context.java'),
                str(out / 'android/content/DialogInterface.java'), str(out / 'android/app/AlertDialog.java'),
                str(out / 'android/widget/EditText.java'), str(java / 'TouchLayout.java'),
                str(java / 'StartupCheats.java'), str(out / 'PortingActionsTest.java')], check=True)
subprocess.run(['java', '-cp', str(out), 'com.halo.decomp.PortingActionsTest'], check=True)
