/**
 * Common useful functions.
 *
 * @author Andrey Naumenko
 */

function focusElement(elementId) {
  var field = document.getElementById(elementId);
  if (field) {
    field.focus();
  }
}

function changeAllItemStatus(element, areaId) {
    $j("#"+areaId+" INPUT[type='checkbox']:not(:disabled)").prop("checked", element.checked);
}

function changeItemStatus(element, areaId, selectAllElemId) {
    if(!element.checked && $(selectAllElemId).checked) {
        $(selectAllElemId).checked = false;
    }

    checkedCount = $j("#"+areaId+" INPUT[type='checkbox']:checked").not("INPUT[id='"+selectAllElemId+"']").size();
    disabledCount = $j("#"+areaId+" INPUT[type='checkbox']:disabled").not("INPUT[id='"+selectAllElemId+"']").size();
    allCount = $j("#"+areaId+" INPUT[type='checkbox']").not("INPUT[id='"+selectAllElemId+"']").size();

    if (checkedCount + disabledCount == allCount && checkedCount > 0) {
        $(selectAllElemId).checked = true;
    }
}

function message(content, life, closable, styleClass) {
    var messages = $j(".message");

    function remove() {
        message.remove();

        var top = 33;
        $j(".message").each(function() {
            $j(this).css({"top" : top + "px"});
            top += ($j(this).outerHeight() + 5);
        });
    }

    var message = $j("<div />").addClass("message").html(content);

    if (closable !== false) {
        message.addClass("closable").click(remove);
    }
    if (styleClass) {
        message.addClass(styleClass);
    }

    var top;
    if (messages.length) {
        var lastMessage = $j(messages[messages.length - 1]);
        top = lastMessage.position().top + lastMessage.outerHeight() + 5 + "px";
    } else {
        top = "33px";
    }

    message.css({"top": top});
    $j("body").append(message);

    if (life > -1) {
        setTimeout(remove, life);
    }
}

/**
 * EPBDS-4825 rich:popupPanel has an issue: https://issues.jboss.org/browse/RF-10980
 * This function fixes rich:popupPanel's processTabindexes() function to correctly handle TABs in dialog boxes
 */
function fixTabIndexesInRichPopupPanels() {
    if (!RichFaces.ui.PopupPanel) {
        return;
    }
    RichFaces.ui.PopupPanel.prototype.processTabindexes = function (input) {
        if (!this.firstOutside) {
            this.firstOutside = input;
        }
        if (!input.prevTabIndex) {
            input.prevTabIndex = input.tabIndex;
            // input.tabIndex = -1; // This line was original implementation. It was replaced with the lines below:
            if ($j(input).closest('.rf-pp-cntr').length === 0) {
                // Replace tab indexes with -1 only for inputs outside of popup panel.
                // Tab indexes inside popup panel are not touched.
                input.tabIndex = -1;
            }
        }
        if (!input.prevAccessKey) {
            input.prevAccessKey = input.accessKey;
            input.accessKey = "";
        }
    };
}

/**
 * Add 'popup-panel' class to a parent div of every rich:popupPanel because that div adds 'display: inline-block;'
 * and it adds unnecessary line break even if "visibility:hidden" is set. We override the style of every .popup-panel
 * to remove that unnecessary line breaks.
 */
function initPopupPanels() {
    if (!$j) {
        return;
    }
    const popupPanels = $j('.rf-pp-cntr').parent("div");
    popupPanels.addClass('popup-panel');

    // EPBDS-10407. By default Popup Panels are rendered with style="visibility:hidden; display: inline-block;" and
    // because of this child div with z-index==100 causes in chrome resize icon for textarea appearing despite that it's
    // hidden.
    // Fix it by forcing display:none with jQuery.
    popupPanels.hide();
}

function updateSubmitListener(listener) {
    if (!$j) {
        return;
    }
    $j("form input[type='submit']").each(function () {
        var $submit = $j(this);
        // Add showLoader listener only on submit buttons without onclick handler to skip
        // ajax jsf submit buttons and buttons with existed logic in onclick attribute.
        // Also skip buttons with their own Loader Handlers (buttons for file download).
        if (!$submit.attr("onclick") && !$submit.hasClass('own-loader-handler')) {
            // Because this function is invoked on DOM change we must remove the handler we've set before.
            $submit.off("click", listener);
            $submit.on("click", listener);
        }
    });
}

function showAnimatedPanel(loadingPanel) {
    loadingPanel.show();

    // EPBDS-6231 Workaround for IE.
    // IE freezes animation when the form is submitted or url is changed. The trick below with replacing of html
    // makes IE think that the gif is a new img element and IE animates it.
    // Html must be replaced after form is submitted - that's why timeout is used.
    setTimeout(function() {loadingPanel.html(loadingPanel.html());}, 1);
}

/**
 * Fix the bug related to not updating input when enter too big number and then lose the focus.
 *
 * @param id the id of inputNumberSpinner element
 */
function fixInputNumberSpinner(id) {
    var component = RichFaces.$(id);
    if (!component) {
        return;
    }

    component.__setValue = function (value, event, skipOnchange) {
        if (!isNaN(value)) {
            if (value > component.maxValue) {
                value = component.maxValue;
            } else if (value < component.minValue) {
                value = component.minValue;
            }
            // !!! The line below was changed. See inputNumberSpinner.js for comparison.
            if (Number(value) !== Number(component.input.val()) || event && event.type === 'change') {
                component.input.val(value);
                component.value = value;
                if (component.onchange && !skipOnchange) {
                    component.onchange.call(component.element[0], event);
                }
            }
        }
    };
}

function initExpandableLinks() {
    if (!$j) {
        return;
    }

    $j('.expandable').off().click(function () {
        $j(this).next().show();
        $j(this).hide();
    })
}

/**
 * Resize the panel when it's content is changed. Works only for panels with autosized="true".
 *
 * @param panelName panel name to resize
 */
function resizePopupPanel(panelName) {
    const panel = RichFaces.$(panelName);
    panel.hide();
    panel.show();
}

/**
 * To fix EPBDS-9950: if clear uploaded file in the pop-up, it cannot be uploaded again.
 */
function fixFileUpload() {
    if (!RichFaces.ui.FileUpload) {
        return;
    }

    const delegate = RichFaces.ui.FileUpload.prototype.__addFiles;
    RichFaces.ui.FileUpload.prototype.__addFiles = function(files) {
        delegate.call(this, files);

        // EPBDS-9950
        this.input.val("")
    };
}

/**
 * Dynamically change PopupPanel size if it's content changes.
 */
function makePopupPanelsReallyResizable() {
    if (!RichFaces.ui.PopupPanel) {
        return;
    }

    const showDelegate = RichFaces.ui.PopupPanel.prototype.show;
    RichFaces.ui.PopupPanel.prototype.show = function (event, opts) {
        showDelegate.call(this, event, opts);
        const self = this;

        if ($j.browser.msie) {
            // ResizeObserver is not supported by IE 11, so we use MutationObserver instead.
            const observer = new MutationObserver(function () {
                self.doResizeOrMove(RichFaces.ui.PopupPanel.Sizer.Diff.EMPTY);
            });
            observer.observe(this.contentDiv.get(0), {
                attributes: true,
                childList: true,
                subtree: true
            });
            this['__observer'] = observer;
        } else {
            const observer = new ResizeObserver(function () {
                self.doResizeOrMove(RichFaces.ui.PopupPanel.Sizer.Diff.EMPTY);
            });
            observer.observe(this.contentDiv.get(0));
            this['__observer'] = observer;
        }
    };

    const hideDelegate = RichFaces.ui.PopupPanel.prototype.hide;
    RichFaces.ui.PopupPanel.prototype.hide = function (event, opts) {
        hideDelegate.call(this, event, opts);
        const observer = this['__observer'];
        observer && observer.disconnect();
    };
}

/**
 * Fix all RichFaces issues.
 */
function fixRichFaces() {
    if (!RichFaces) {
        return;
    }

    fixTabIndexesInRichPopupPanels();
    fixFileUpload();
    initPopupPanels();
    makePopupPanelsReallyResizable();
}

function is4xxStatus(code) {
    if (typeof code !== "number") {
        return false;
    }
    return ~~(code / 100) === 4;
}

// () => - necessary so that the second part of the replace() is not interpreted as a regex.
String.prototype.replaceString = function (regex, string) {
    return this.replace(regex, () => string);
}

String.prototype.replaceAllString = function (regex, string) {
    return this.replaceAll(regex, () => string);
}

String.prototype.unescapeHTML = function() {
    return this.replace(/&amp;/g,'&')
        .replace(/&lt;/g,'<')
        .replace(/&gt;/g,'>')
        .replace(/&nbsp;/g,' ');
}
