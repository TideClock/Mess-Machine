package org.teavm.jso.typedarrays;
public class Float32Array { public final float[] a; public Float32Array(int n){a=new float[n];}
 public static Float32Array create(int n){return new Float32Array(n);} public int getLength(){return a.length;}
 public float get(int i){return a[i];} public void set(int i,float v){a[i]=v;} }
