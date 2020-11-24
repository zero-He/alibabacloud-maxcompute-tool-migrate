/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.aliyun.odps.datacarrier.taskscheduler.ui;

import static j2html.TagCreator.a;
import static j2html.TagCreator.button;
import static j2html.TagCreator.div;
import static j2html.TagCreator.form;
import static j2html.TagCreator.input;
import static j2html.TagCreator.label;
import static j2html.TagCreator.li;
import static j2html.TagCreator.span;
import static j2html.TagCreator.tbody;
import static j2html.TagCreator.ul;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import j2html.TagCreator;
import j2html.tags.DomContent;

public abstract class PagedTable <T> {
  private static final Logger LOG = LogManager.getLogger(PagedTable.class);

  private String tableId;
  private String tableCssClass;
  private String pageSizeFormField;
  private String prevPageSizeFormField;
  private String pageNumberFormField;
  private PagedDataSource<T> dataSource;

  public PagedTable(
      String tableId,
      String tableCssClass,
      String pageSizeFormField,
      String prevPageSizeFormField,
      String pageNumberFormField,
      PagedDataSource<T> dataSource) {
    this.tableId = tableId;
    this.tableCssClass = tableCssClass;
    this.pageSizeFormField = pageSizeFormField;
    this.prevPageSizeFormField = prevPageSizeFormField;
    this.pageNumberFormField = pageNumberFormField;
    this.dataSource = dataSource;
  }

  public abstract DomContent[] getHeaders();

  public abstract DomContent getRow(T t);

  public abstract String pageLink(int page);

  public abstract String goButtonFormPath();

  public String getPageSizeFormField() {
    return pageSizeFormField;
  }

  public String getPrevPageSizeFormField() {
    return prevPageSizeFormField;
  }

  public String getPageNumberFormField() {
    return pageNumberFormField;
  }

  /**
   * Return a page navigation.
   *
   * @param page Page number.
   * @param pageSize Page size.
   * @param totalPages Number of total pages.
   * @return
   */
  private DomContent pageNavigation(int page, int pageSize, int totalPages) {
    LOG.info("Enter pageNavigation({}, {}, {})", page, pageSize, totalPages);
    if (totalPages == 1) {
      return null;
    }

    int groupSize = 10;
    // Included
    int firstGroup = 0;
    // Included
    int lastGroup = (totalPages - 1) / groupSize;
    int currentGroup = (page - 1) / groupSize;
    // Included
    int startPage = currentGroup * pageSize + 1;
    // Included
    int endPage = Math.min(totalPages, startPage + groupSize - 1);
    List<DomContent> pageTags = new LinkedList<>();
    for (int i = startPage; i <= endPage; i++) {
      if (i == page) {
        pageTags.add(
            li(
                a(Integer.toString(i)).withHref("#")
            ).withClass("disabled")
        );
      } else {
        pageTags.add(
            li(
                a(Integer.toString(i)).withHref(pageLink(i))
            )
        );
      }
    }

    List<DomContent> listEntries = new LinkedList<>();
    // Append "<<"
    if (currentGroup > firstGroup) {
      listEntries.add(li(
          a(
              span("<<").attr("aria-hidden=\"true\"")
          ).withHref(pageLink(startPage - groupSize))
                       .attr("aria-label=\"Previous Group\"")
      ));
    }
    // Append "<"
    if (page > 1) {
      listEntries.add(li(
          a(
              span("<").attr("aria-hidden=\"true\"")
          ).withHref(pageLink(page - 1))
           .attr("aria-label=\"Previous\"")
      ));
    }
    listEntries.addAll(pageTags);
    // Append ">"
    if (page < totalPages) {
      listEntries.add(li(
          a(
              span(">")
          ).withHref(pageLink(page + 1))
           .attr("aria-label=\"Next\"")
      ));
    }
    // Append ">>"
    if (currentGroup < lastGroup) {
      listEntries.add(li(
          a(
              span(">>")
          ).withHref(pageLink(startPage + groupSize))
           .attr("aria-label=\"Next Group\"")
      ));
    }

    return div(
      // Form for jumping to a page
      div(
        form(
            input().withType("hidden")
                   .withName(prevPageSizeFormField)
                   .withValue(Integer.toString(pageSize)),
            label(Integer.toString(totalPages) + " Pages. Jump to ")
                .withStyle("margin-right: 3px; margin-left: 3px"),
            input().withType("text")
                   .withName(pageNumberFormField)
                   .withId("form-" + tableId + "-page-no")
                   .withValue(Integer.toString(page))
                   .withClass("span1"),
            label(". Show ").withStyle("margin-right: 3px; margin-left: 3px"),
            input().withType("text")
                   .withName(pageSizeFormField)
                   .withId("form-" + tableId + "-page-size")
                   .withValue(Integer.toString(pageSize))
                   .withClass("span1"),
            label("items in a page.").withStyle("margin-right: 3px; margin-left: 3px"),
            button("Go").withType("submit").withClass("btn")
        ).withId("form-" + tableId + "-page")
         .withMethod("get")
         .withAction(goButtonFormPath())
         .withClass("form-inline pull-right")
         .withStyle("margin-bottom: 0px;")
      ),
      // Page navigation
      div(
        span("Page: ").withStyle("float: left; padding-top: 4px; padding-right: 4px;"),
        ul(
          listEntries.toArray(new DomContent[0])
        )
      ).withClass("pagination").withStyle("margin-bottom: 5px;")
    );
  }

  /**
   * Return a table.
   *
   * @param page Page number.
   * @return
   */
  public DomContent table(int page) {
    int totalPages = dataSource.totalPages();

    // Make sure page is a valid number
    if (page > totalPages || page <= 0) {
      LOG.warn("Invalid page number: {}, total pages: {}", page, totalPages);
      page = 1;
    }

    PageData<T> pageData = dataSource.pageData(page);
    DomContent[] headers = getHeaders();
    DomContent pageNav = pageNavigation(
        Math.min(totalPages, page),
        dataSource.getPageSize(),
        pageData.getTotalPage());

    List<DomContent> tableContent = new LinkedList<>();
    tableContent.addAll(Arrays.asList(headers));

    if (!pageData.getData().isEmpty()) {
      tableContent.add(tbody(
          pageData.getData().stream().map(this::getRow).toArray(DomContent[]::new)
      ));
    }

    if (pageNav != null) {
      return div(
          pageNav,
          TagCreator.table(
              tableContent.toArray(new DomContent[0])
          ).withClass(tableCssClass).withId(tableId)
      );
    } else {
      return div(
          TagCreator.table(
              tableContent.toArray(new DomContent[0])
          ).withClass(tableCssClass).withId(tableId)
      );
    }
  }
}
