/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.watch;

/**
 *
 * <p> 详情见: https://redkale.org
 * @author zhangjx
 */
interface WatchNode {

    public String getName();

    public String getDescription();

    public long getValue();

    public boolean isInterval();
}
