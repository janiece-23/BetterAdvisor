package core;

public @interface Table {
    String name();
    boolean inSubTable() default true;
}
