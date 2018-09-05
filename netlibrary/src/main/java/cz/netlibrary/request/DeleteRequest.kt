package cz.netlibrary.request


import cz.netlibrary.model.RequestMethod

/**
 * Created by cz on 2017/6/7.
 */
class DeleteRequest{
    var method=RequestMethod.delete
    var info:String?=null
    var url:String=String()
    var pathValue:Array<String>?=null
    var params:Map<String,Any?>?=null
    var header:Map<String,String>?=null
}