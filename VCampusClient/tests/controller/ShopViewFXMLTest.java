package controller;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;

/** 无图形渲染环境下验证商店 FXML 的图片控件与事件连线。 */
public class ShopViewFXMLTest {
    public static void main(String[] args) throws Exception {
        try (InputStream input = ShopViewFXMLTest.class.getResourceAsStream(
                "/resources/fxml/ShopView.fxml")) {
            if (input == null) throw new AssertionError("找不到 ShopView.fxml");
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input);
            requireId(document, "adminProductImageView");
            requireId(document, "adminImagePlaceholderLabel");
            requireId(document, "shopUploadStatusLabel");
            Element replaceButton = requireId(document, "replaceProductImageButton");
            if (!"#handleReplaceProductImage".equals(replaceButton.getAttribute("onAction"))) {
                throw new AssertionError("更换图片按钮未连接处理方法");
            }
            ShopController.class.getDeclaredMethod("handleReplaceProductImage");
        }
        System.out.println("ShopViewFXMLTest PASS");
    }

    private static Element requireId(Document document, String id) {
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            if (id.equals(element.getAttribute("fx:id"))) return element;
        }
        throw new AssertionError("FXML 缺少控件：" + id);
    }
}
