package org.redkale.test.rest;

import javax.persistence.Id;
import org.redkale.convert.json.JsonFactory;
import org.redkale.net.http.*;
import org.redkale.source.VirtualEntity;

@VirtualEntity
public class HelloEntity {

    @Id
    private int helloid;

    private String helloname;

    private int creator;

    private long updatetime;

    private long createtime;

    @RestHeader(name = "hello-res")
    private String resname;

    @RestBody
    private String bodystr;

    @RestBody
    private byte[] bodys;

    @RestAddress
    private String clientaddr;

    /** 以下省略getter setter方法 */
    public int getHelloid() {
        return helloid;
    }

    public void setHelloid(int helloid) {
        this.helloid = helloid;
    }

    public String getHelloname() {
        return helloname;
    }

    public void setHelloname(String helloname) {
        this.helloname = helloname;
    }

    public long getUpdatetime() {
        return updatetime;
    }

    public void setUpdatetime(long updatetime) {
        this.updatetime = updatetime;
    }

    public long getCreatetime() {
        return createtime;
    }

    public void setCreatetime(long createtime) {
        this.createtime = createtime;
    }

    public int getCreator() {
        return creator;
    }

    public void setCreator(int creator) {
        this.creator = creator;
    }

    public String getClientaddr() {
        return clientaddr;
    }

    public void setClientaddr(String clientaddr) {
        this.clientaddr = clientaddr;
    }

    public String getResname() {
        return resname;
    }

    public void setResname(String resname) {
        this.resname = resname;
    }

    public String getBodystr() {
        return bodystr;
    }

    public void setBodystr(String bodystr) {
        this.bodystr = bodystr;
    }

    public byte[] getBodys() {
        return bodys;
    }

    public void setBodys(byte[] bodys) {
        this.bodys = bodys;
    }

    @Override
    public String toString() {
        return JsonFactory.root().getConvert().convertTo(this);
    }
}
