package entity;
import java.io.Serializable;
public class StudentChangeItem implements Serializable {
    private static final long serialVersionUID=1L;
    private Long itemId,requestId;
    private String fieldName,oldValue,newValue;
    public Long getItemId() {
        return itemId;
    }
    public void setItemId(Long v) {
        itemId=v;
    }
    public Long getRequestId() {
        return requestId;
    }
    public void setRequestId(Long v) {
        requestId=v;
    }
    public String getFieldName() {
        return fieldName;
    }
    public void setFieldName(String v) {
        fieldName=v;
    }
    public String getOldValue() {
        return oldValue;
    }
    public void setOldValue(String v) {
        oldValue=v;
    }
    public String getNewValue() {
        return newValue;
    }
    public void setNewValue(String v) {
        newValue=v;
    }
}
