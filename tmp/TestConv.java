public class TestConv {
  public static void main(String[] args) throws Exception {
    String s = "\u6D63\u72B2\u30BD";
    String c1 = new String(s.getBytes("GBK"), "UTF-8");
    String c2 = new String(s.getBytes("UTF-8"), "GBK");
    String c3 = new String(s.getBytes("ISO-8859-1"), "UTF-8");
    System.out.println("c1=" + c1);
    System.out.println("c2=" + c2);
    System.out.println("c3=" + c3);
    for (int i=0;i<c1.length();i++) System.out.print(Integer.toHexString(c1.charAt(i))+" ");
    System.out.println();
  }
}
