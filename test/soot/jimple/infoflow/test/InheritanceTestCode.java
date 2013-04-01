package soot.jimple.infoflow.test;

import java.util.LinkedList;
import java.util.List;

import soot.jimple.infoflow.test.android.ConnectionManager;
import soot.jimple.infoflow.test.android.TelephonyManager;
import soot.jimple.infoflow.test.utilclasses.D1static;

public class InheritanceTestCode {
	
	public void testInheritance1(){
		boolean x = false;
		D1static d1 = new D1static("");
		x = d1.start();
		x= d1.taintIt();
		if(x){
			d1.toString();
		}
	}
	
	public void testInheritance2(){
		String taint = TelephonyManager.getDeviceId();
		T2b b = new T2b();
		b.addToList(taint);
		ConnectionManager cm = new ConnectionManager();
		cm.publish(b.a.get(0));
	}
	
	class T2a{
		List<String> a;
		
		void addToList(String t){
			a = new LinkedList<String>();
			List<String> b = a;
			b.add(t);
		}
	}
	
	class T2b extends T2a{
		
	}

}
