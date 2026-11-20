package com.lcodexs.coursealarm;

import android.graphics.*;

/** Java/RenderNode adapter for Backdrop's unchanged AGSL and LiquidButton recipe. */
final class LiquidCardEffect {
    private final RenderNode content = new RenderNode("liquid-card-background");
    private final RenderNode shadow = new RenderNode("liquid-card-shadow");
    private LinearGradient tint; private int tintColor;
    private final RenderNode edge = new RenderNode("liquid-card-highlight");
    private final RuntimeShader lens = new RuntimeShader(BackdropShaders.RoundedRectRefractionShaderString);
    private final RuntimeShader highlight = new RuntimeShader(BackdropShaders.DefaultHighlightShaderString);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final Matrix inverse = new Matrix();
    private final float density;
    private float width, height, radius, bottomRadius;
    private final int padding;
    LiquidCardEffect(float density) { this.density = density; padding = (int)Math.ceil(4*density); content.setClipToBounds(false); edge.setClipToBounds(false); }
    boolean needsRecording() { return !content.hasDisplayList() || !shadow.hasDisplayList() || !edge.hasDisplayList(); }
    void update(float width, float height, float radius, Matrix cardToRoot, GlassScene scene) {
        update(width, height, radius, radius, cardToRoot, scene);
    }
    void update(float width, float height, float radius, float bottomRadius, Matrix cardToRoot, GlassScene scene) {
        boolean resized = this.width != width || this.height != height || this.radius != radius || this.bottomRadius != bottomRadius;
        this.width = width; this.height = height; this.radius = radius; this.bottomRadius = bottomRadius;
        if (!cardToRoot.invert(inverse)) return;
        int w = (int)Math.ceil(width) + padding*2, h = (int)Math.ceil(height) + padding*2;
        content.setPosition(-padding, -padding, w-padding, h-padding);
        RecordingCanvas canvas = content.beginRecording(w, h);
        try { canvas.translate(padding, padding); canvas.concat(inverse); canvas.drawRenderNode(scene.node); }
        finally { content.endRecording(); }
        if (resized || !shadow.hasDisplayList() || !edge.hasDisplayList()) {
            tint=null;
            int shadowPadding=(int)Math.ceil(48*density);
            shadow.setPosition(-shadowPadding,-shadowPadding,(int)Math.ceil(width)+shadowPadding,(int)Math.ceil(height)+shadowPadding);
            RecordingCanvas sc=shadow.beginRecording();
            float[] corners = {radius,radius,radius,radius,bottomRadius,bottomRadius,bottomRadius,bottomRadius};
            shape.reset(); shape.addRoundRect(0, 0, width, height, corners, Path.Direction.CW);
            try { paint.reset();paint.setAntiAlias(true);paint.setColor(0x1A000000);sc.translate(shadowPadding,shadowPadding+4*density);sc.drawPath(shape,paint); }
            finally { shadow.endRecording(); }
            shadow.setRenderEffect(RenderEffect.createBlurEffect(24*density,24*density,Shader.TileMode.DECAL));
            lens.setFloatUniform("size", width, height); lens.setFloatUniform("offset", -padding, -padding);
            lens.setFloatUniform("cornerRadii", radius, radius, bottomRadius, bottomRadius);
            lens.setFloatUniform("refractionHeight", 12*density); lens.setFloatUniform("refractionAmount", -24*density); lens.setFloatUniform("depthEffect", 0f);
            ColorMatrix saturation = new ColorMatrix(new float[]{
                    1.3935f,-0.3575f,-0.036f,0,0, -0.1065f,1.1425f,-0.036f,0,0, -0.1065f,-0.3575f,1.464f,0,0, 0,0,0,1,0});
            RenderEffect colors = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(saturation));
            RenderEffect blur = RenderEffect.createBlurEffect(2*density, 2*density, colors, Shader.TileMode.CLAMP);
            content.setRenderEffect(RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(lens, "content"), blur));
            highlight.setFloatUniform("size", width, height); highlight.setFloatUniform("cornerRadii", radius, radius, bottomRadius, bottomRadius);
            highlight.setColorUniform("color", Color.WHITE); highlight.setFloatUniform("angle", (float)Math.PI/4); highlight.setFloatUniform("falloff", 1f);
            edge.setPosition(0,0,(int)Math.ceil(width),(int)Math.ceil(height));
            RecordingCanvas e = edge.beginRecording();
            try { paint.reset(); paint.setAntiAlias(true); paint.setShader(highlight); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(density); paint.setAlpha(128); e.drawPath(shape,paint); }
            finally { edge.endRecording(); }
            Paint blend=new Paint();blend.setBlendMode(BlendMode.PLUS);edge.setUseCompositingLayer(true,blend);
            edge.setRenderEffect(RenderEffect.createBlurEffect(0.25f*density,0.25f*density,Shader.TileMode.DECAL));
        }
    }
    void draw(Canvas canvas, int courseColor) {
        draw(canvas, courseColor, true);
    }
    void draw(Canvas canvas, int courseColor, boolean showCourseTint) {
        canvas.drawRenderNode(shadow);
        int save = canvas.save(); canvas.clipPath(shape); canvas.drawRenderNode(content);
        // Smoothstep gradient over only the bottom quarter, max alpha 0.18.
        if (showCourseTint) {
        if(tint==null||tintColor!=courseColor){
        int[] colors = new int[9]; float[] positions = new float[9];
        for (int i=0;i<9;i++) { float t=i/8f; positions[i]=t; colors[i]=(Math.round(255*0.18f*t*t*(3-2*t))<<24)|(courseColor&0xFFFFFF); }
        tint=new LinearGradient(0,height*0.75f,0,height,colors,positions,Shader.TileMode.CLAMP);tintColor=courseColor;
        }
        paint.reset(); paint.setAntiAlias(true); paint.setShader(tint);
        canvas.drawRect(0,height*0.75f,width,height,paint); paint.setShader(null);
        }
        int layer = canvas.saveLayer(null, null); canvas.drawRenderNode(edge); canvas.restoreToCount(layer);
        canvas.restoreToCount(save);
    }
    void release() { content.discardDisplayList(); edge.discardDisplayList(); shadow.discardDisplayList(); }
}
