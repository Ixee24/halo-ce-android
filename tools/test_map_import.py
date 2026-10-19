"""Execute TouchControls' actual map filename validation with edge cases."""
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parent.parent
out = root/'build/map-import-tests'
out.mkdir(parents=True, exist_ok=True)
source = (root/'port/android/app/src/main/java/com/halo/decomp/TouchControls.java').read_text()


def method(name):
    match = re.search(r'^    static (?:String|boolean) '+name+r'\(', source, re.M)
    if not match:
        raise ValueError(name)
    start = source.index('{', match.start())
    depth, end = 1, start+1
    while depth:
        depth += (source[end] == '{')-(source[end] == '}')
        end += 1
    return source[match.start():end]

code = r'''
public class MapImportTest {
'''+method('sanitizeMapName')+method('isResourceMapName')+method('sanitizeModName')+r'''
 static void check(boolean condition, String message) {
  if (!condition) throw new RuntimeException("Map import regression: "+message);
 }
 public static void main(String[] arguments) {
  check("bloodgulch.map".equals(sanitizeMapName("bloodgulch.map")), "plain name kept");
  check("a30.map".equals(sanitizeMapName("a30.MAP")), "uppercase extension kept with lowercase suffix");
  check("a30.map".equals(sanitizeMapName("/sdcard/Download/a30.map")), "picker path stripped to leaf");
  check("a30.map".equals(sanitizeMapName("content://media/a30.map")), "content URI stripped to leaf");
  check(sanitizeMapName("a30.txt") == null, "non-map rejected");
  check(sanitizeMapName("a30") == null, "extensionless rejected");
  check(sanitizeMapName(".map") == null, "empty stem rejected");
  check(sanitizeMapName("   .map") == null, "blank stem rejected");
  check(sanitizeMapName(null) == null, "null rejected");
  check("my_map-2 (1).map".equals(sanitizeMapName("my_map-2 (1).map")), "safe punctuation kept");
  check("b_c_d_e_f_g_h_i.map".equals(sanitizeMapName("a/b:c*d?e\"f<g>h|i.map")), "unsafe characters replaced");
  String kept = sanitizeMapName("x".repeat(60)+".map");
  check(kept != null && kept.equals("x".repeat(51)+".map"), "stem truncated to 51 characters");
  check("trailing.map".equals(sanitizeMapName("trailing...   .map")), "trailing dots and spaces trimmed");
  check(isResourceMapName("bitmaps.map") && isResourceMapName("SOUNDS.MAP") && isResourceMapName("loc.map"),
   "shared resource maps recognized");
  check(!isResourceMapName("bloodgulch.map") && !isResourceMapName(null), "ordinary maps not shared");

  check("hd_audio".equals(sanitizeModName("hd_audio")), "plain mod folder name kept");
  check("hd_audio".equals(sanitizeModName("hd_audio.zip")), "zip suffix dropped");
  check("HD Textures".equals(sanitizeModName("HD Textures.ZIP")), "uppercase zip suffix dropped with spaces kept");
  check("german".equals(sanitizeModName("/sdcard/Download/german")), "folder path stripped to leaf");
  check("high-res (v2)".equals(sanitizeModName("high-res (v2)")), "safe punctuation kept in a mod name");
  check("a_b_c".equals(sanitizeModName("a:b?c")), "unsafe characters replaced in a mod name");
  check(sanitizeModName(null) == null && sanitizeModName("") == null && sanitizeModName("   ") == null,
   "empty mod names rejected");
  check(sanitizeModName(".zip") == null, "a bare zip suffix leaves no name");
  check("trimmed".equals(sanitizeModName("trimmed...   .zip")), "trailing dots and spaces trimmed from a mod name");
  check("x".repeat(63).equals(sanitizeModName("x".repeat(80))), "mod name cut to 63 bytes");
  check(sanitizeModName(new String(Character.toChars(0x00e9)).repeat(40)).length() == 31, "multibyte mod name cut on a character boundary");

  System.out.println("Map filename validation, truncation and rejection passed");
  System.out.println("Mod folder name validation, zip suffix and 63-byte limit passed");
 }
}
'''
(out/'MapImportTest.java').write_text(code, encoding='utf-8')
subprocess.run(['javac','-d',str(out),str(out/'MapImportTest.java')], check=True)
subprocess.run(['java','-cp',str(out),'MapImportTest'], check=True)
