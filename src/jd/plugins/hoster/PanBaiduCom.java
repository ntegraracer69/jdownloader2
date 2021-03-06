//jDownloader - Downloadmanager
//Copyright (C) 2010  JD-Team support@jdownloader.org
//
//This program is free software: you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation, either version 3 of the License, or
//(at your option) any later version.
//
//This program is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//GNU General Public License for more details.
//
//You should have received a copy of the GNU General Public License
//along with this program.  If not, see <http://www.gnu.org/licenses/>.

package jd.plugins.hoster;

import jd.PluginWrapper;
import jd.config.Property;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

//All links come from a decrypter
@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "pan.baidu.com" }, urls = { "http://(www\\.)?pan\\.baidudecrypted\\.com/\\d+" }, flags = { 0 })
public class PanBaiduCom extends PluginForHost {

    public PanBaiduCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getAGBLink() {
        return "http://pan.baidu.com/";
    }

    private String              DLLINK                                     = null;
    private static final String TYPE_FOLDER_LINK_NORMAL_PASSWORD_PROTECTED = "http://(www\\.)?pan\\.baidu\\.com/share/init\\?shareid=\\d+\\&uk=\\d+";
    private static final String NOCHUNKS                                   = "NOCHUNKS";
    private static final String USER_AGENT                                 = "netdisk;4.6.4.1;PC;PC-Windows;6.3.9600;WindowsBaiduYunGuanJia";

    private static final String NICE_HOST                                  = "pan.baidu.com";
    private static final String NICE_HOSTproperty                          = "panbaiducom";

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink downloadLink) throws Exception {
        if (downloadLink.getBooleanProperty("offline", false)) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        // Other or older User-Agents might get slow speed
        br.getHeaders().put("User-Agent", USER_AGENT);
        // From decrypter
        DLLINK = downloadLink.getStringProperty("dlink", null);
        // From host plugin
        if (DLLINK == null) {
            DLLINK = downloadLink.getStringProperty("panbaidudirectlink", null);
        }
        if (DLLINK == null) {
            // We might need to enter a captcha to get the link so let's just stop here
            downloadLink.setAvailable(true);
            return AvailableStatus.TRUE;
        }
        DLLINK = DLLINK.replace("\\", "");
        final Browser br2 = br.cloneBrowser();
        br2.setFollowRedirects(true);
        URLConnectionAdapter con = null;
        try {
            con = br2.openGetConnection(DLLINK);
            if (!con.getContentType().contains("html")) {
                downloadLink.setDownloadSize(con.getLongContentLength());
                // Make sure only to use one property
                downloadLink.setProperty("panbaidudirectlink", DLLINK);
            } else {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
        } finally {
            try {
                con.disconnect();
            } catch (Throwable e) {
            }
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink downloadLink) throws Exception, PluginException {
        requestFileInformation(downloadLink);
        String passCode = downloadLink.getStringProperty("pass", null);
        DLLINK = checkDirectLink(downloadLink, "panbaidudirectlink");
        if (DLLINK == null) {
            final String original_url = downloadLink.getStringProperty("mainLink", null);
            final String shareid = downloadLink.getStringProperty("origurl_shareid", null);
            final String uk = downloadLink.getStringProperty("origurl_uk", null);
            final String link_password = downloadLink.getStringProperty("important_link_password", null);
            final String link_password_cookie = downloadLink.getStringProperty("important_link_password_cookie", null);
            if (link_password_cookie != null) {
                br.setCookie("http://pan.baidu.com/", "BDCLND", link_password_cookie);
            }
            br.getPage(original_url);

            /* Experimental code */
            final String i_frame = br.getRegex("<iframe src=\"(http://pan\\.baidu\\.com/share/link\\?shareid=\\d+\\&uk=\\d+\\&t=[A-Za-z0-9]+)\"").getMatch(0);
            if (i_frame != null) {
                logger.info("Found i_frame - accessing it!");
                br.getPage(i_frame);
            } else {
                logger.info("Found no i_frame");
            }

            /* Fallback handling if the password cookie didn't work */
            if (link_password != null && br.getURL().matches(TYPE_FOLDER_LINK_NORMAL_PASSWORD_PROTECTED)) {
                br.postPage("http://pan.baidu.com/share/verify?" + "vcode=&shareid=" + shareid + "&uk=" + uk + "&t=" + System.currentTimeMillis(), "&pwd=" + Encoding.urlEncode(link_password));
                if (!br.containsHTML("\"errno\":0")) {
                    // Wrong password -> Impossible
                    logger.warning("pan.baidu.com: Couldn't download password protected link even though the password is given...");
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                br.getPage(original_url);
            }
            final String sign = br.getRegex("FileUtils\\.share_sign=\"([a-z0-9]+)\"").getMatch(0);
            final String tsamp = br.getRegex("FileUtils\\.share_timestamp=\"(\\d+)\"").getMatch(0);
            if (sign == null || tsamp == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            final String fsid = downloadLink.getStringProperty("important_fsid", null);
            final String postLink = "http://pan.baidu.com/share/download?channel=chunlei&clienttype=0&web=1&uk=" + uk + "&shareid=" + shareid + "&timestamp=" + tsamp + "&sign=" + sign + "&bdstoken=null&channel=chunlei&clienttype=0&web=1";
            br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
            br.postPage(postLink, "fid_list=%5B" + fsid + "%5D");
            String code = null;
            for (int i = 1; i <= 3; i++) {
                final String captchaLink = getJson("img");
                if (captchaLink == null) {
                    break;
                }
                final String captchaid = new Regex(captchaLink, "([A-Z0-9]+)$").getMatch(0);
                try {
                    code = getCaptchaCode(captchaLink, downloadLink);
                } catch (final Throwable e) {
                    logger.info("Captcha download failed -> Retrying!");
                    throw new PluginException(LinkStatus.ERROR_RETRY, "Captcha download failed");
                }
                br.postPage(postLink, "fid_list=%5B" + fsid + "%5D&input=" + Encoding.urlEncode(code) + "&vcode=" + captchaid);
            }
            if (getJson("img") != null) {
                throw new PluginException(LinkStatus.ERROR_CAPTCHA);
            }
            if (br.containsHTML("\"errno\":\\-20")) {
                handlePluginBroken(downloadLink, "unknownerror20", 3);
            } else if (br.containsHTML("\"errno\":112")) {
                handlePluginBroken(downloadLink, "unknownerror112", 3);
            }
            DLLINK = getJson("dlink");
            if (DLLINK == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }

        int maxChunks = 0;
        if (downloadLink.getBooleanProperty(NOCHUNKS, false)) {
            maxChunks = 1;
        }

        dl = jd.plugins.BrowserAdapter.openDownload(br, downloadLink, DLLINK, true, maxChunks);
        if (dl.getConnection().getContentType().contains("html")) {
            br.followConnection();
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        downloadLink.setFinalFileName(Encoding.htmlDecode(getFileNameFromHeader(dl.getConnection())));
        if (passCode != null) {
            downloadLink.setProperty("pass", passCode);
        }
        downloadLink.setProperty("panbaidudirectlink", DLLINK);
        try {
            if (!this.dl.startDownload()) {
                try {
                    if (dl.externalDownloadStop()) {
                        return;
                    }
                } catch (final Throwable e) {
                }
                /* unknown error, we disable multiple chunks */
                if (downloadLink.getBooleanProperty(PanBaiduCom.NOCHUNKS, false) == false) {
                    downloadLink.setProperty(PanBaiduCom.NOCHUNKS, Boolean.valueOf(true));
                    throw new PluginException(LinkStatus.ERROR_RETRY);
                }
            }
        } catch (final PluginException e) {
            // New V2 errorhandling
            /* unknown error, we disable multiple chunks */
            if (e.getLinkStatus() != LinkStatus.ERROR_RETRY && downloadLink.getBooleanProperty(PanBaiduCom.NOCHUNKS, false) == false) {
                downloadLink.setProperty(PanBaiduCom.NOCHUNKS, Boolean.valueOf(true));
                throw new PluginException(LinkStatus.ERROR_RETRY);
            }
            throw e;
        }
    }

    private String getJson(final String parameter) {
        br.getRequest().setHtmlCode(br.toString().replace("\\", ""));
        String result = br.getRegex("\"" + parameter + "\":(\\d+)").getMatch(0);
        if (result == null) {
            result = br.getRegex("\"" + parameter + "\":\"([^<>\"]*?)\"").getMatch(0);
        }
        return result;
    }

    private String checkDirectLink(final DownloadLink downloadLink, final String property) {
        String dllink = downloadLink.getStringProperty(property);
        if (dllink != null) {
            try {
                final Browser br2 = br.cloneBrowser();
                br2.setFollowRedirects(true);
                URLConnectionAdapter con = br2.openGetConnection(dllink);
                if (con.getContentType().contains("html") || con.getLongContentLength() == -1) {
                    downloadLink.setProperty(property, Property.NULL);
                    dllink = null;
                }
                con.disconnect();
            } catch (final Exception e) {
                downloadLink.setProperty(property, Property.NULL);
                dllink = null;
            }
        }
        return dllink;
    }

    /**
     * Is intended to handle out of date errors which might occur seldom by re-tring a couple of times before throwing the out of date
     * error.
     * 
     * @param dl
     *            : The DownloadLink
     * @param error
     *            : The name of the error
     * @param maxRetries
     *            : Max retries before out of date error is thrown
     */
    private void handlePluginBroken(final DownloadLink dl, final String error, final int maxRetries) throws PluginException {
        int timesFailed = dl.getIntegerProperty(NICE_HOSTproperty + "failedtimes_" + error, 0);
        dl.getLinkStatus().setRetryCount(0);
        if (timesFailed <= maxRetries) {
            logger.info(NICE_HOST + ": " + error + " -> Retrying");
            timesFailed++;
            dl.setProperty(NICE_HOSTproperty + "failedtimes_" + error, timesFailed);
            throw new PluginException(LinkStatus.ERROR_RETRY, "Final download link not found");
        } else {
            dl.setProperty(NICE_HOSTproperty + "failedtimes_" + error, Property.NULL);
            logger.info(NICE_HOST + ": " + error + " -> Plugin is broken");
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
    }

    @Override
    public void reset() {
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }


/* NO OVERRIDE!! We need to stay 0.9*compatible */
public boolean hasCaptcha(DownloadLink link, jd.plugins.Account acc) {
return true;
}
}