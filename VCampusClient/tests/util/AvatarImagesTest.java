package util;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import javax.imageio.ImageIO;

public class AvatarImagesTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        BufferedImage source = new BufferedImage(800, 400, BufferedImage.TYPE_INT_ARGB);
        for (int y=0;y<400;y++) for (int x=0;x<800;x++) source.setRGB(x,y,x<400?0xffff0000:0xff0000ff);
        BufferedImage right = ImageIO.read(new ByteArrayInputStream(AvatarImages.crop(source,400,0,400)));
        check(right.getWidth()==512 && right.getHeight()==512,"output dimensions");
        check(right.getRGB(256,256)==0xff0000ff,"selected source region");
        BufferedImage center = ImageIO.read(new ByteArrayInputStream(AvatarImages.crop(source,200,0,400)));
        check(center.getRGB(100,256)==0xffff0000 && center.getRGB(412,256)==0xff0000ff,"crop preserves aspect ratio");
        try { AvatarImages.crop(source,700,0,400); throw new AssertionError("invalid bounds accepted"); }
        catch (IllegalArgumentException expected) { }
        BufferedImage tiny = new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB); tiny.setRGB(0,0,0x80112233);
        BufferedImage enlarged = ImageIO.read(new ByteArrayInputStream(AvatarImages.crop(tiny,0,0,1)));
        check((enlarged.getRGB(256,256)>>>24)==128,"transparent source preserved");
        var path = Files.createTempFile("avatar-test-", ".png");
        try {
            ImageIO.write(new BufferedImage(5000,100,BufferedImage.TYPE_INT_RGB),"png",path.toFile());
            BufferedImage decoded=AvatarImages.read(path.toFile());
            check(decoded.getWidth()<=2048 && decoded.getHeight()>0,"large source subsampling");
            Files.writeString(path,"not an image");
            try { AvatarImages.read(path.toFile()); throw new AssertionError("invalid image accepted"); }
            catch(java.io.IOException expected) { }
        } finally { Files.deleteIfExists(path); }
        System.out.println("PASS: crop region, aspect ratio, output size, bounds, alpha, subsampling, invalid image");
    }
}
