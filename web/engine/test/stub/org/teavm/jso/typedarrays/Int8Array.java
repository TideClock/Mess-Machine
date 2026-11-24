package org.teavm.jso.typedarrays;
public class Int8Array { public final byte[] a; public Int8Array(byte[] b){a=b;} public static Int8Array create(int n){return new Int8Array(new byte[n]);} public int getLength(){return a.length;} public byte get(int i){return a[i];} public void set(int i, byte v){a[i]=v;} }
