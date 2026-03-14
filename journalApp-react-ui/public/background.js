chrome.runtime.onInstalled.addListener(() => {
    chrome.contextMenus.create({
        id: "validateTextMenu",
        title: "Validate with AI Fact Checker",
        contexts: ["selection"]
    });
});

chrome.contextMenus.onClicked.addListener((info, tab) => {
    if (info.menuItemId === "validateTextMenu") {
        // Save the selected text to storage and open the popup
        chrome.storage.local.set({ selectedTextToValidate: info.selectionText }, () => {
            // In Manifest V3, we can't easily open the popup programmatically.
            // We will open a new window specifically sized for the extension.
            const popupUrl = chrome.runtime.getURL("index.html");
            chrome.windows.create({
                url: popupUrl,
                type: "popup",
                width: 450,
                height: 600
            });
        });
    }
});
